# ADR-007: Evaluate expiry lazily; retire links without deleting them

- **Status:** Accepted
- **Date:** 2026-08-19
- **Affects:** `ShortLink.isResolvable`, `LinkCache`, `LinkController.deactivate`

## Context

Links can carry an expiry, and owners can retire a link early. Both need to take effect
promptly and neither may leave a window where a dead link still redirects.

## Decision

**Expiry is evaluated at read time**, not swept by a background job. A sweeper adds a moving
part for no user-visible benefit, and it introduces a window — between expiry and the next
sweep — in which a dead link still works. Lazy evaluation means a missed job or a clock
skew can never resurrect a link.

**Retirement is a flag, not a delete.** `DELETE /api/v1/links/{code}` sets `active = false`.
The row and its click history survive.

**Expired and retired links return 404, not 410.** A 410 tells a scanner that this code was
once real, which narrows its search for other valid codes.

## Consequences

- Every resolution checks `active` and `expires_at`. This costs nothing on the database
  path and, on the cache path, is handled by capping the cache TTL at the link's remaining
  lifetime — an expired link cannot outlive its cache entry.
- A retired code is never reissued, because codes come from a monotonic sequence. A user
  who bookmarked it gets a stable 404 rather than someone else's destination, which is the
  failure mode that matters.
- The table grows monotonically. At this scale that is not a problem; at a larger one,
  retired links older than a retention period move to cold storage. That is a data
  lifecycle decision that needs a legal answer about analytics retention, not an
  engineering one, so it is deliberately left open.
- Analytics survive retirement, so a campaign report stays intact after its links are
  taken down.
