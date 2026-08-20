#!/usr/bin/env python3
"""
Traffic spike drill.

Measures what the service actually does when offered more load than it can serve,
rather than what the architecture document claims it does. Each phase reports the
status distribution and the latency tail, because a spike that returns errors
quickly and a spike that hangs are very different failures.

Usage: python3 scripts/spike-test.py

Environment:
  SHORTENER_BASE_URL  service under test (default http://localhost:8080)
  SPIKE_USERNAME      account to authenticate as (default alice)
  SPIKE_PASSWORD      its password (default alice-password)
"""
import json
import os
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

BASE = os.environ.get("SHORTENER_BASE_URL", "http://localhost:8080").rstrip("/")
USERNAME = os.environ.get("SPIKE_USERNAME", "alice")
PASSWORD = os.environ.get("SPIKE_PASSWORD", "alice-password")

# Resolved from this file rather than the working directory, so the drill can be started from
# anywhere and still find the Compose project it needs to stop Redis in.
REPO_ROOT = Path(__file__).resolve().parent.parent


def request(method, path, body=None, token=None, timeout=15):
    req = urllib.request.Request(f"{BASE}{path}", method=method)
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    data = None
    if body is not None:
        req.add_header("Content-Type", "application/json")
        data = json.dumps(body).encode()
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(req, data, timeout=timeout) as response:
            return response.status, (time.perf_counter() - started) * 1000, response.read()
    except urllib.error.HTTPError as e:
        return e.code, (time.perf_counter() - started) * 1000, e.read()
    except Exception as e:
        return type(e).__name__, (time.perf_counter() - started) * 1000, b""


def redirect(code):
    """Does not follow the redirect; we are measuring this service, not example.com."""
    opener = urllib.request.build_opener(NoRedirect)
    req = urllib.request.Request(f"{BASE}/{code}")
    started = time.perf_counter()
    try:
        with opener.open(req, timeout=15) as response:
            return response.status, (time.perf_counter() - started) * 1000
    except urllib.error.HTTPError as e:
        return e.code, (time.perf_counter() - started) * 1000
    except Exception as e:
        return type(e).__name__, (time.perf_counter() - started) * 1000


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def report(label, results):
    statuses = {}
    for status, _ in results:
        statuses[status] = statuses.get(status, 0) + 1
    latencies = sorted(latency for _, latency in results)
    p50 = statistics.median(latencies)
    p99 = latencies[int(len(latencies) * 0.99) - 1]
    spread = ", ".join(f"{status}: {count}" for status, count in sorted(statuses.items(), key=str))
    print(f"  {label:<34} {spread:<34} p50 {p50:6.1f}ms  p99 {p99:7.1f}ms  max {latencies[-1]:7.1f}ms")
    return statuses


def spike(fn, args, concurrency):
    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        return list(pool.map(fn, args))


def redis(action):
    subprocess.run(["docker", "compose", action, "redis"], cwd=REPO_ROOT, capture_output=True)


def main():
    status, _, body = request("POST", "/api/v1/auth/token",
                              {"username": USERNAME, "password": PASSWORD})
    if status != 200:
        print(f"Could not authenticate as {USERNAME} ({status}). Is {BASE} running?")
        sys.exit(1)
    token = json.loads(body)["accessToken"]

    status, _, body = request("POST", "/api/v1/links", {"url": "https://example.com/spike"}, token)
    code = json.loads(body)["code"]
    redirect(code)  # warm the cache

    print("\nPHASE 1  redirect spike, everything healthy (2000 requests, 200 concurrent)")
    report("cache hit path", spike(redirect, [code] * 2000, 200))

    print("\nPHASE 2  redirect spike, Redis stopped (2000 requests, 200 concurrent)")
    print("         every request now misses cache and hits PostgreSQL through a 20-connection pool")
    redis("stop")
    time.sleep(3)
    statuses = report("cache miss storm", spike(redirect, [code] * 2000, 200))

    print("\nPHASE 3  create spike while Redis is down (300 requests, 100 concurrent)")
    print("         the rate limiter is Redis-backed, so this shows what fail-open costs")
    created = spike(lambda i: request("POST", "/api/v1/links",
                                      {"url": f"https://example.com/flood/{i}"}, token)[:2],
                    range(300), 100)
    report("writes, limiter unavailable", created)

    redis("start")
    time.sleep(6)

    print("\nPHASE 4  create spike, Redis healthy (300 requests, 100 concurrent)")
    print("         burst 20, refill 60/min: most of this should be refused")
    report("writes, limiter enforcing", spike(
        lambda i: request("POST", "/api/v1/links",
                          {"url": f"https://example.com/limited/{i}"}, token)[:2],
        range(300), 100))

    print("\nPHASE 5  recovery check")
    report("redirect after Redis returns", spike(redirect, [code] * 200, 50))

    print()
    return statuses


if __name__ == "__main__":
    main()
