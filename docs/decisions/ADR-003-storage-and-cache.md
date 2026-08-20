# ADR-003: PostgreSQL of record, Redis as an optional accelerator

- **Status:** Accepted
- **Date:** 2026-08-19
- **Affects:** `LinkCache`, `application.yml`, Flyway migrations

## Context

The workload is a read-heavy key-value lookup with a roughly 100:1 read/write ratio, plus
aggregate queries over click events.

## Options considered

**PostgreSQL alone.** Simple, transactional, and entirely adequate at prototype scale.
Every redirect becomes a database round trip, and a scanner hitting random codes generates
one query per bogus request.

**A dedicated key-value store as the system of record** (DynamoDB, ScyllaDB). The right
answer at ten billion links. At this scale it trades away transactions, joins for analytics,
and operational familiarity for headroom nobody is using yet.

**PostgreSQL plus Redis cache-aside.** Keeps relational guarantees for the write path and
the analytics queries, and removes most reads from the database.

## Decision

PostgreSQL is the system of record. Redis is a read-through cache with a 10-minute TTL, and
it is **optional**: every Redis interaction is wrapped so an outage degrades the service to
"slower", never to "down".

Three properties of the cache are load-bearing:

- **Misses are cached** for 30 seconds. Unknown codes are the most common request under
  scanning traffic; without a negative entry, every bogus code is a database round trip and
  a scanner becomes a denial-of-service tool.
- **The entry TTL is capped at the link's remaining lifetime.** This is what makes it safe
  to serve a cached hit without re-checking expiry, and it means an expired link can never
  keep redirecting from cache.
- **Eviction happens after the write**, so a concurrent read cannot repopulate the cache
  from pre-deactivation state.

Redis is excluded from the health indicator. A soft dependency that flips the readiness
probe would pull healthy pods out of rotation during a cache incident, turning a degradation
into an outage.

## Consequences

- Redis timeouts are set to 200 ms. Waiting longer on an unhealthy cache would be slower
  than skipping it entirely, which defeats the purpose.
- Invalidation is best effort. If eviction fails the stale entry expires within the TTL,
  which is why the TTL is short rather than indefinite.
- The integration tests run with Redis pointed at a dead port, so the degraded path is
  exercised on every single test run rather than being the thing nobody ever tried.
