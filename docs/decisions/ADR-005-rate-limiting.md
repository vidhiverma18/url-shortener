# ADR-005: Token bucket rate limiting on creation, failing open

- **Status:** Accepted
- **Date:** 2026-08-19
- **Affects:** `RateLimiter`, `LinkController`

## Context

Link creation is the only endpoint that writes unbounded data. Left open, it is the
cheapest way to fill the database and to mint links for a phishing campaign in bulk.

## Options considered

**Fixed window counter.** Trivial, and lets a caller send two full windows' worth of
traffic across a window boundary — the burst it was meant to prevent.

**Sliding window log.** Accurate, but stores a timestamp per request per client.

**Token bucket.** Allows a controlled burst, enforces a sustained rate, and needs two
numbers per client.

## Decision

Token bucket in Redis: 20 burst, 60 per minute by default, keyed by API key when present
and by client address otherwise.

The bucket update runs inside a **Lua script** so read-modify-write is atomic on the Redis
side. With separate GET and SET calls, concurrent requests from one client each observe
the same token count and all pass — the limiter fails precisely when it is needed.

**It fails open.** If Redis is unreachable, requests are allowed.

## Consequences

- Failing open is an availability-over-enforcement choice, and it is the **wrong default
  for an endpoint that costs money or writes unbounded data**. It is acceptable here
  because this is a prototype without authentication, and because a cache outage taking
  down link creation would be a worse first failure than a brief window of unlimited
  creation. In production this flips to fail-closed with a small in-process fallback
  bucket, and that change is one method.
- IP-based identification is weak. It is trivially bypassed with a pool of addresses, and
  it over-restricts users behind shared NAT. Real enforcement needs authenticated clients,
  which is out of scope per A-4.

**Superseded in part by [ADR-008](ADR-008-authentication-and-ownership.md).** Creation is
now keyed by the authenticated principal rather than the client address, which removes both
weaknesses noted above: a pool of IPs no longer bypasses the bucket, and callers behind
shared NAT no longer share one. The login endpoint keeps an address-keyed bucket, since a
login attempt has no principal yet — and keying it by the *submitted* username would let an
attacker lock out a known account simply by failing to log in as them.
- `X-Forwarded-For` is trusted for its first entry only, and only because this service
  expects to sit behind a load balancer that overwrites it. A client-supplied value is
  spoofable; this is a convenience for correct deployments, not a security control.
- The redirect path is deliberately **not** rate limited. Throttling redirects punishes
  visitors for the behaviour of a link's owner. Scanning abuse is absorbed by the negative
  cache instead (see [ADR-003](ADR-003-storage-and-cache.md)).
