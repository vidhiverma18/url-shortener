# ADR-004: Analytics are asynchronous, batched, and best-effort

- **Status:** Accepted
- **Date:** 2026-08-19
- **Affects:** `ClickRecorder`, `click_events`, `LinkStatsResponse`

## Context

Every redirect should produce a click record. The redirect is also the latency- and
availability-critical path in the entire system.

## Options considered

**Increment a counter column on `short_links`.** One statement, always consistent. Also
serialises every redirect for a popular link behind a single row lock. This is the specific
mistake that has taken down real shorteners: the row-lock contention on a viral link stalls
the connection pool and the whole service follows.

**Insert one row per redirect, synchronously.** No lock contention, but it puts an insert,
a flush and a commit between the visitor and their destination, and couples redirect
availability to write availability of the analytics table. It violates NFR-6 directly.

**Kafka, consumed by a separate aggregator.** The correct production shape. It is also a
second piece of infrastructure, a consumer service, and a deployment story — none of which
can be built credibly here, and a half-built version would be worse than an honest simpler one.

## Decision

Buffer click events in a bounded in-memory queue (10,000) and flush them to PostgreSQL in
batches of up to 500, roughly once a second.

Analytics are **best-effort**, and this is stated in three places: here, in the class
documentation, and in the `accuracyNote` field of every stats response, so a caller cannot
mistake these numbers for billing-grade.

## Consequences

- **Events in the buffer are lost if the process is killed** — bounded by about one second
  of traffic. Accepted: a shortener that stops redirecting to protect its metrics has its
  priorities backwards.
- **Events are dropped when the buffer fills.** Dropping is deliberate; the alternative is
  back-pressure onto the redirect path. Drops are counted and logged on a doubling curve so
  a sustained overload does not turn the logger into the outage.
- **A database failure abandons the batch** rather than retrying, because retrying against a
  broken database grows the buffer without bound.
- Counts are eventually consistent, lagging by roughly the flush interval.
- The migration path is a `ClickRecorder` swap. The component boundary was drawn here for
  exactly that reason.
