# AI collaboration log

How AI was used, what it produced, and — the part that matters — what was rejected and why.

The working principle: **AI drafts, the engineer decides.** Every artifact here was read
before it was kept. The rejections below are not a list of the tool's failures; they are the
record of where engineering judgment was actually applied, which is the only place it is
visible after the fact.

## How tasks were framed

Prompts that produce usable output carry four things: intent, constraints, acceptance
criteria, and enough technical context to make the constraints meaningful. A representative
one, for the analytics work:

> Add per-link click analytics to this Spring Boot service. **Hard constraint:** the
> redirect path must not block on, or fail because of, an analytics write — treat that as
> inviolable. Existing redirect behaviour and its tests must not change. **Acceptance:**
> totals, unique visitors, daily series and top referrers are queryable per link; a
> database failure in the analytics path leaves redirects working; the buffer is bounded
> and its overflow behaviour is explicit. Context: PostgreSQL of record, Redis optional,
> `RedirectController` is the hot path.

Compare with "add analytics", which produces a counter column and a row-lock outage.

## Traceability

| Area | Disposition | Note |
| --- | --- | --- |
| Project scaffolding, Compose, Dockerfile | Generated, kept | Reviewed for pinned images, non-root user, healthchecks |
| Base62 codec | Generated, edited | Fixed-width padding added; unbounded encode rejected |
| Feistel permutation | Generated from an engineer-specified design | The *design* was the human decision; the implementation was drafted and then verified by property tests |
| `UrlValidator` | Generated, heavily edited | First version checked scheme only; the SSRF and credential cases were added by hand |
| `ClickRecorder` | Generated twice, second kept | See rejection 3 |
| `LinkCache` | Generated, edited | Negative caching and the expiry-capped TTL were not in the draft |
| `RateLimiter` Lua script | Generated, kept | Reviewed for atomicity, which is the whole point of using Lua here |
| Tests | Generated, curated | Roughly a third were deleted as coverage theatre; see rejection 5 |
| ADRs and scenarios | Drafted from engineer-supplied decisions | The decisions came first; AI wrote them up |

## Rejections, with rationale

**1. Hash-and-truncate short codes.** Proposed as the default design. Rejected on three
counts: it needs a read before every write, it still collides at 42 bits of output, and it
maps the same URL to the same code, silently merging two campaigns' analytics. Replaced with
sequence → Feistel → Base62. *Signal: the most common answer online is not the same as the
correct one for the stated requirements.*

**2. `301 Moved Permanently` for the redirect.** Proposed with a comment that it is faster.
It is faster because it stops reaching the service, which ends analytics and makes link
retirement impossible. Replaced with 302 plus explicit no-store headers.

**3. A click-count column incremented on redirect.** The first analytics draft. Rejected:
serialises every redirect for a popular link behind one row lock, and it fails only under
the traffic you most want to survive. Replaced with an append-only table and a buffered
writer.

**4. `@Transactional` on the batch flush method.** Plausible and wrong: the flush is a
self-invocation from the scheduled method, so the Spring proxy never applies it. The
annotation would have read as a guarantee it does not provide. Removed, with the reason left
as a comment so it does not get "fixed" back.

**5. Assertion-free and tautological tests.** Several generated tests called a method and
asserted it did not throw, and one asserted a mock returned what the mock was told to
return. Deleted. A test that cannot fail is worse than no test, because it reports safety
that does not exist.

**6. Resolving DNS to block SSRF.** Suggested as a hardening step. Rejected: resolve-time
checks are defeated by DNS rebinding, so this buys confidence without buying safety. The
limitation is documented and pushed to the network layer instead, which is where it can
actually be enforced.

**7. A generic `catch (Exception)` returning 500 as the only error handler.** Kept
initially as a backstop, and it then swallowed a deliberate 400 into a 500 — caught by an
integration test. A specific handler now sits in front of it. *This one is worth recording
precisely because the mistake made it into the code: the test suite caught what the review
did not.*

**8. `@Transactional(readOnly = true)` on the redirect resolution method.** Generated,
reviewed, and **accepted — wrongly.** It reads as correct Spring practice for a method that
may query the database. It is not: Spring begins the transaction before the method body,
taking a pool connection *ahead of* the cache lookup, so a cache hit still depended on a
healthy database. With PostgreSQL stopped, cached redirects returned 500 after a 3-second
stall — while the architecture document confidently claimed they would keep working.

Nothing in 55 tests caught it, because the failure only appears with the database actually
down. It was found by running the failure drill instead of trusting the document. *The
lesson is the one worth generalising: idiomatic-looking code passed review precisely because
it looked idiomatic, and the claim in the docs was believed rather than tested. The
annotation is gone, and `ShortLinkResolutionTest` now asserts its absence with the reason
attached.*

## Where AI was most and least useful

**Most:** mechanical breadth. Boilerplate, DTO and mapper code, the Lua token bucket,
migration syntax, and generating the hostile-input table for the validator — the last of
which produced two cases (`instance-data`, `metadata.goog`) that would probably have been
missed by hand.

**Least:** anything where the right answer depends on a priority that is not in the code.
Every one of the seven rejections above is a case where the generated code was reasonable in
isolation and wrong against a stated constraint. AI does not know that the redirect is the
product. That has to be decided, written down, and enforced.

## Secure usage

No credentials, internal hostnames, or proprietary code were placed in prompts. The one
machine-specific workaround this environment required — a Maven mirror, because the host's
`/etc/hosts` blackholes Maven Central — lives outside the repository in `~/.m2/settings.xml`
so the project stays portable and no local network detail leaks into the deliverable.
