# ADR-010: Reuse an owner's existing link for a repeated URL

- **Status:** Accepted
- **Date:** 2026-08-19
- **Closes:** the non-idempotent-create gap recorded in the readiness audit
- **Affects:** `ShortLinkService`, `UrlFingerprint`, `V4` migration, `CreateLinkRequest`

## Context

Posting the same URL twice produced two different codes. Two problems follow.

A client that retries after a timeout silently creates a duplicate, because it cannot tell
whether its first attempt succeeded. That is the readiness-audit finding, and it is a
correctness issue rather than a tidiness one.

Separately, every repeat consumed a sequence value, a row and an index entry to store
something already stored.

## Decision

`POST /api/v1/links` returns the owner's existing link when the same URL is submitted again,
answering **200** instead of **201**. The status is the signal: a client retrying after a
timeout can tell from it whether the original attempt had already landed.

**Reuse is scoped to the owner.** Returning another user's link would disclose that they had
shortened the URL, merge two users' clicks into one analytics series, and hand back a code
the caller can neither read stats for nor retire — contradicting [ADR-008](ADR-008-authentication-and-ownership.md)
three separate ways.

**Reuse applies only to the plain case:** no custom alias, no explicit expiry, and no
`forceNew`. Each exclusion is a request for something specific that an existing link cannot
satisfy. An alias names a particular code. An expiry sets a lifetime the existing link does
not have. `forceNew` exists to opt out. Answering any of them with an old link answers a
different question from the one asked.

`forceNew` is there because reuse-by-default removes a capability that previously existed:
two codes for one destination is a legitimate thing to want, since separate campaigns
pointing at one landing page need separate click counts.

### Matching

A `url_hash` column stores the SHA-256 of a canonical form of the URL. Hashed rather than
indexed directly because `original_url` runs to 2048 characters, which can exceed
PostgreSQL's b-tree entry limit once multi-byte characters are involved.

**The stored URL is never rewritten.** Canonicalisation produces a lookup key only. Query
parameter order can be load-bearing, and a signed or tokenised URL breaks the moment anything
reorders or re-encodes it, so the destination stays byte-for-byte what the caller supplied.

Normalisation covers only what RFC 3986 defines as equivalent for HTTP: scheme and host
lowercased, a default port dropped, and an empty path treated as `/`. Everything else is
compared exactly, using the raw path, query and fragment so percent-encoding is never
altered.

A trailing slash is therefore **significant** — `/a` and `/a/` are different resources on
plenty of real servers. The asymmetry drives this: under-normalising costs one extra row,
while over-normalising sends a caller somewhere they never asked to go.

### Concurrency

The application-side lookup cannot win a race on its own — concurrent identical requests all
read before any of them writes. A partial unique index is the real arbiter:

```sql
CREATE UNIQUE INDEX ux_short_links_owner_url
    ON short_links (created_by, url_hash)
    WHERE url_hash IS NOT NULL AND active;
```

A caller that loses the race catches the constraint violation, re-reads, and returns the
winner's link — the race resolves into the intended answer rather than an error. A test
starts 24 threads on one URL and asserts they all receive the same code and that exactly one
row exists.

The index is partial on `active` so retiring a link releases its slot: shortening the same
URL afterwards correctly yields a new code rather than resurrecting the retired one.

`create` is deliberately **not** `@Transactional`. A failed insert marks the surrounding
transaction rollback-only, so the lost-race path could not re-read the winner's row inside
one. Each repository call carries its own transaction and nothing here spans two writes that
must succeed together.

## Consequences

- **Reuse is best-effort by design.** It never returns a *wrong* link, but it will miss
  matches that a more aggressive normaliser would catch — a duplicate row is the acceptable
  failure, a wrong redirect is not.
- **Creation now costs one extra indexed read** in the reusable case. Creation is not the
  hot path and this is dwarfed by the insert it often avoids.
- **This is not full idempotency.** It deduplicates by URL, not by request. Two genuinely
  distinct requests that happen to share a URL are indistinguishable from a retry, which is
  why `forceNew` exists. An `Idempotency-Key` header keyed on the request as a whole remains
  the more general answer and would also cover aliases and expiries.
- **Links created before this change have a null `url_hash`** and never participate. They
  are not retro-fitted, because backfilling would silently merge links whose owners may have
  wanted them separate.
