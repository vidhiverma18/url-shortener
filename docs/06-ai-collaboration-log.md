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
integration test. A specific handler was added in front of it. *This one is worth recording
precisely because the mistake made it into the code: the test suite caught what the review
did not.*

**7b. The same defect, fixed only where it had been observed.** Adding a
`ResponseStatusException` handler resolved the symptom that a test had caught, and the
underlying fault — a catch-all advice intercepting exceptions before Spring can assign them
a status — was left in place. Probing the API later showed *five* client errors still
reported as `500`: malformed JSON, a non-numeric query parameter, an unsupported method, an
unsupported content type, and an empty body. `GlobalExceptionHandler` now extends
`ResponseEntityExceptionHandler`, so Spring's own mappings apply and the catch-all sees only
genuinely unexpected failures. *The lesson is about the first fix, not the bug: it treated
the failing test as the specification. The test described one instance of the fault; nothing
prompted a check for the others, and a passing suite made it look finished.*

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

**9. Securing every route, including the redirect.** The first generated filter chain ended
with `.anyRequest().authenticated()`. Uniform and defensible-looking, and it would have
broken the product: every short link already shared with the world would start returning
401. Rewritten so `GET /{code}` is explicitly public and everything else is default-deny.
*Same root cause as the general lesson below — the model applied a security default without
knowing which route is the product.*

**10. Returning 403 to a caller who does not own a link.** The literally correct status, and
the wrong one here: 403 confirms the code exists, handing an enumeration attacker the one
bit the Feistel permutation was chosen to withhold. Changed to 404, consistent with the
expired-link decision in ADR-007.

**11. Seeding demo users from a SQL migration with hardcoded BCrypt hashes.** Convenient,
and it commits a working credential to git forever, where it will outlive the demo and get
copied into something real. Replaced with a flag-gated runtime seeder that hashes at
startup, logs a warning every time, and puts no usable secret in the repository.

**12. Keying the login rate limiter by username.** Reads as more precise than keying by
address, and inverts the control into a denial-of-service tool: an attacker locks any known
account out by deliberately failing to log in as them. Kept address-keyed, with the reason
in a comment.

**13. "The service already fails open, so a cache outage is handled."** My own summary, and
the generated resilience review agreed with it. Both were reasoning from the drills that had
been run, and every one of those drills *stopped* a container. Stopped is the easy case: the
kernel refuses the connection instantly. Pausing the container instead — alive, accepting
TCP, never answering — dropped redirect throughput from about 14,000 requests per second to
221, with every request paying two 200ms timeouts forever. The fix is
[ADR-009](decisions/ADR-009-circuit-breaking.md). *The lesson is about the test, not the
code: a passing failure drill had been treated as proof the failure mode was handled, when
it only proved the easier half of it was. "Is the dependency down" and "is the dependency
answering" are different questions.*

**14. Config that advertised a capability the build could not provide.** `application.yml`
exposed `prometheus` under the actuator endpoints, and it read as complete — the property is
real, the spelling is right, and nothing fails at startup. But `micrometer-registry-prometheus`
was never a dependency, so the endpoint answered 404. *Recorded because of when it would have
been discovered: not by a test, not at deploy, but by whoever went looking for metrics during
an incident. Configuration is a claim about the system, and this one was checked only by
reading it.*

**15. Deduplicating URLs globally, and normalising them aggressively.** The obvious
implementation: hash the URL, look it up across the whole table, and tidy the URL first by
lowercasing it, stripping the trailing slash and sorting query parameters. Wrong twice.
Global scope would have returned another user's link, disclosing that they had shortened the
URL, merging two users' analytics and handing back a code the caller cannot manage. And the
tidying is unsound — `/a` and `/a/` are different resources on plenty of servers, and query
order is load-bearing in signed URLs, so it would have redirected people somewhere they never
asked to go. Scoped to the owner, and normalisation cut back to what RFC 3986 actually
defines as equivalent. *The asymmetry is the thing worth naming: under-normalising costs one
extra row, over-normalising costs a wrong redirect, so the two errors are nowhere near equally
bad and the safe direction is obvious once stated.*

**16. Catching the unique-constraint violation and re-reading inside the same transaction.**
Reads correctly and cannot work: a failed insert marks the transaction rollback-only, so the
recovery query fails too. The method is now untransactional, letting each repository call
carry its own boundary — the same class of mistake as the `@Transactional` defects in entries
4 and 8, which is three separate bugs in this project caused by assuming a Spring annotation
does what it looks like it does.

## Where AI was most and least useful

**Most:** mechanical breadth. Boilerplate, DTO and mapper code, the Lua token bucket,
migration syntax, and generating the hostile-input table for the validator — the last of
which produced two cases (`instance-data`, `metadata.goog`) that would probably have been
missed by hand.

**Least:** anything where the right answer depends on a priority that is not in the code.
Every one of the rejections above is a case where the generated code was reasonable in
isolation and wrong against a stated constraint. AI does not know that the redirect is the
product. That has to be decided, written down, and enforced.

The security work sharpened this. Spring Security is heavily represented in training data,
so the generated configuration was fluent and mostly right — and its three mistakes were all
the same mistake: applying a sound *general* default (secure every route, return the
technically correct status, seed data in a migration) to a specific system whose constraints
point the other way. Fluency in the framework is exactly what makes those defaults slip
through review.

## Secure usage

No credentials, internal hostnames, or proprietary code were placed in prompts. The one
machine-specific workaround this environment required — a Maven mirror, because the host's
`/etc/hosts` blackholes Maven Central — lives outside the repository in `~/.m2/settings.xml`
so the project stays portable and no local network detail leaks into the deliverable.
