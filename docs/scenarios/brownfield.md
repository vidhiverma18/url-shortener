# Scenario 2 — Brownfield: add analytics and reliability to a working service

**Requirement as given:** "...with analytics and reliability features."

By this point the service from [scenario 1](greenfield.md) worked and had users in the
hypothetical sense that matters: **any change now has a blast radius.** The redirect path
was fast and simple, and the obvious implementation of analytics would have made it slow and
fragile.

## Codebase reasoning: what this change touches

Before writing anything, the impact was traced from the entry point outward.

| Module | Impact | Risk introduced |
| --- | --- | --- |
| `RedirectController` | **Hot path.** Gains an analytics call and a cache lookup | Any latency or exception added here hits every visitor of every link |
| `ShortLinkService.resolve` | Cache-aside logic wraps the existing database read | Stale reads; expired links served from cache |
| `short_links` schema | Unchanged | — |
| New `click_events` table | Additive migration | Write volume equal to redirect volume |
| `LinkController` | New stats endpoint | Aggregate queries over a growing table |
| Health and readiness | New Redis dependency | **A soft dependency could start failing readiness probes** |

That last row was the most valuable output of the analysis. It is invisible from the diff
and it is how a cache incident becomes an outage.

## Decomposition

| # | Task | Depends on | Guardrail |
| --- | --- | --- | --- |
| 1 | `click_events` schema, append-only | — | No counter column on `short_links` |
| 2 | Buffered, batched `ClickRecorder` | 1 | Must never block or throw into the redirect path |
| 3 | Wire the recorder into the redirect | 2 | Fire-and-forget only |
| 4 | Cache-aside `LinkCache` | — | Must fail open |
| 5 | Negative caching | 4 | Bound scanner impact |
| 6 | Token bucket rate limiting | — | Creation only, never redirects |
| 7 | Stats endpoint | 1 | Bounded window; must state its own accuracy |
| 8 | Exclude Redis from health | 4 | Identified by the impact analysis above |

## Execution: three rejections that shaped the result

**A counter column on `short_links`.** The generated first pass added `click_count` and
incremented it on redirect. Rejected: it serialises every redirect for a popular link behind
one row lock — the exact failure that has taken down real shorteners, and it appears only
under the traffic you most want to survive. Replaced with an append-only table.

**`@Async` with one insert per click.** Better, but it is a thread and a transaction per
redirect, and it fails invisibly under load. Replaced with a bounded queue drained in
batches of up to 500, which sheds load explicitly and counts what it sheds.
[ADR-004](../decisions/ADR-004-async-analytics.md).

**`@Transactional` on the flush method.** Generated and plausible. Rejected: it is a
self-invocation from the scheduled method, so the Spring proxy would never apply it and the
annotation would be decoration that reads as a guarantee. `saveAll` carries its own
transaction, which also gives each batch an independent failure boundary. The reason is now
a comment on the method so the next person does not "fix" it back.

## Validation

- Existing tests kept passing throughout: the contract of scenario 1 did not move.
- The cache-unavailable path is not a special test, it is **the default**: the integration
  profile points Redis at a dead port, so every one of the 13 API tests also proves the
  service behaves identically without its cache.
- `ClickRecorderTest` pins the failure modes directly — 10,500 events into a 10,000 slot
  buffer sheds exactly 500 and reports it; a repository that throws abandons the batch and
  does not propagate.
- The analytics test asserts the *asynchrony itself*: after three redirects the stats still
  read zero and the buffer holds three. That is the contract the redirect path depends on,
  so it is the thing worth asserting.

## Two real defects this scenario caught

Both were found by tests, not by reading:

1. **`visitor_hash` was `CHAR(64)`.** Startup validation caught the mismatch against the
   entity. `CHAR` right-pads with spaces, so two identical hashes could have compared
   unequal depending on how they were written. Changed to `VARCHAR(64)`.
2. **The catch-all exception handler swallowed deliberate 4xx responses.** A
   `ResponseStatusException` raised for an out-of-range stats window came back as a 500. A
   generic `@ExceptionHandler(Exception.class)` is a trap that turns every intentional error
   into an internal one; a specific handler now sits in front of it.
