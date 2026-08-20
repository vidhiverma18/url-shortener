# ADR-009: Circuit breaking on the Redis dependency

- **Status:** Accepted
- **Date:** 2026-08-19
- **Extends:** [ADR-003](ADR-003-storage-and-cache.md) (cache fails open), [ADR-005](ADR-005-rate-limiting.md) (limiter fails open)
- **Affects:** `CircuitBreaker`, `LinkCache`, `RateLimiter`

## Context

The design already failed open: if Redis is unreachable, the cache is skipped and the rate
limiter allows the request. Failure drills confirmed this worked, and the architecture
document said so.

Those drills stopped the container. **Stopped is the easy case** — the kernel refuses the
connection immediately, so a "failure" costs microseconds.

Pausing the container instead (`docker compose pause`, i.e. `SIGSTOP`) simulates the harder
and more realistic case: a process that is alive, holds its TCP connections open and never
answers. A Redis under memory pressure, swapping, or in a long GC pause looks exactly like
this. Measured on the redirect path:

| Redis state | Throughput | p99 | Per-request |
| --- | --- | --- | --- |
| Healthy | ~14,000 req/s | 13 ms | 1.7 ms |
| Stopped (refused) | ~9,400 req/s | 36 ms | — |
| **Hung (paused)** | **221 req/s** | **419 ms** | **412 ms** |

412ms is two 200ms timeouts, one on the cache read and one on the write-back, paid on
*every* request with nothing to stop it recurring. Throughput collapsed 64-fold while the
service still returned correct responses.

That is a cascading failure in waiting. At 412ms against a bounded thread pool the ceiling
is a few hundred requests per second; past that, requests queue, latency climbs and threads
exhaust — the service is effectively down because of a dependency documented as optional.

**A timeout bounds one request. Only a breaker bounds the pattern.** No timeout value fixes
this: lowering it reduces the per-request cost but still pays it forever, and lowering it far
enough to be cheap would evict a healthy-but-busy Redis.

## Options considered

**Resilience4j.** The standard answer, with metrics, sliding windows and Spring Boot
integration. Rejected as disproportionate: one dependency, one failure mode, two call sites,
and its own configuration surface to learn and get wrong.

**Lower the Redis timeout.** Cheaper per request and still unbounded in aggregate. Treats the
symptom.

**Fail the health check when Redis is slow.** Actively harmful — it would take healthy
instances out of rotation over a soft dependency, converting a degradation into an outage.

**A small in-process breaker.** Chosen.

## Decision

A dependency-free `CircuitBreaker`: after 5 consecutive failures it opens for 5 seconds,
during which calls are skipped without being attempted. When the cooldown elapses exactly
one probe is admitted; success closes it, failure restarts the cooldown.

**One breaker per dependency, not per call site.** `LinkCache` and `RateLimiter` share it,
because both talk to the same server — a failure seen by either is evidence about that
server, and sharing means the write path never has to rediscover an outage the read path has
already established.

Admitting a *single* probe is the detail that matters. Releasing the full flood at a
dependency that may still be sick is how a recovering system gets knocked straight back
down, so the gate is a compare-and-set with a test asserting that 64 concurrent callers
yield exactly one probe.

The threshold is low (5) because the cost of being wrong is asymmetric: opening needlessly
costs one database read per request, which the system is already proven to handle; staying
closed against a hung server costs a full timeout on every request.

Decoding a cache entry sits outside the breaker. A malformed entry means this service wrote
something wrong, and skipping Redis cannot fix a bug in our own encoding.

## Consequences

Measured after the change, same machine, steady state with the breaker open:

| Redis state | Before | After |
| --- | --- | --- |
| Hung, throughput | 221 req/s | **15,597 req/s** |
| Hung, per request | 412 ms | **~2 ms** |
| Hung, p99 | 419 ms | 19 ms |

A hung Redis now costs roughly what an absent one costs, and recovery is automatic: the
breaker closed on its own within one cooldown of the container being unpaused.

- **Detecting the outage still costs.** The first few requests after Redis hangs pay the
  full timeout, and one probe per cooldown pays it thereafter. This is inherent — the
  breaker cannot know the dependency is sick until something fails.
- **The breaker is per instance.** Each pod learns independently. Correct for this design,
  since sharing that state would require the dependency that just failed.
- **Consecutive-failure counting is cruder than a sliding window.** It cannot express "open
  at 50% failures over 30 seconds", so a dependency failing intermittently at 40% will not
  trip it. Acceptable because the failure mode this defends against — a hung server — fails
  every call, not some of them.
- **No metric is exported yet.** Breaker transitions are logged, but the open/closed state
  should be a gauge before this runs anywhere real.
