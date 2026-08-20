# ADR-002: Redirect with 302 and forbid caching

- **Status:** Accepted
- **Date:** 2026-08-19
- **Affects:** `RedirectController`

## Context

The redirect can be a `301 Moved Permanently` or a `302 Found`. This is the single most
consequential decision in a URL shortener, and it is close to irreversible: a 301 already
sitting in a browser cache cannot be recalled.

## Options considered

**301 Permanent.** Browsers cache it, often indefinitely and often ignoring headers.
Origin load drops to near zero for hot links. In exchange, the second and every subsequent
visit by a given browser never reaches the service. That means clicks stop being counted,
and — worse — retiring a link stops working, because the browser never asks again. A link
issued today would keep resolving to its old destination for as long as that cache entry
survives.

**302 Found.** Every visit reaches the service. Analytics are complete, retirement is
immediate, and destinations remain changeable. The cost is that redirect traffic is real
traffic that must be served.

## Decision

Return `302`, with `Cache-Control: no-store, no-cache, must-revalidate, private`.

The explicit no-cache header is not redundant. Without it a shared proxy would happily
serve the redirect on our behalf, reintroducing exactly the invisibility that choosing 302
was meant to avoid.

`X-Robots-Tag: noindex, nofollow` is also set, so short links do not accumulate search
engine authority that could be resold for spam.

## Consequences

- The origin absorbs the full read volume. This is the load that [ADR-003](ADR-003-storage-and-cache.md)'s
  cache exists to make cheap, and the reason the redirect path does one cache lookup and
  nothing else.
- Analytics are complete rather than sampled, which is what makes FR-6 meaningful.
- A CDN in front of this service would trade analytics fidelity for origin load. That is a
  product decision — it knowingly loses clicks — and it is not taken here.
- If the product ever prefers cheap redirects over accurate counts, this ADR is superseded,
  not amended, and only for links created after the change.
