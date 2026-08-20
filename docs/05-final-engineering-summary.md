# Final engineering summary

## What was built

A URL shortening service: create links, redirect visitors, report click analytics. It runs
on `docker compose up --build`, exposes an OpenAPI-documented REST API, and passes 55 tests
covering the code generation guarantees, hostile input, the analytics failure modes, and the
full API contract against a real PostgreSQL.

Verified end to end on a running instance: redirects return 302 with the correct headers and
serve from cache in ~3 ms; analytics aggregate by day and referrer; SSRF targets are refused
with an RFC 9457 problem document.

## Plan and rationale

The work was sequenced by **reversibility, not by size.** Two decisions are expensive or
impossible to undo once links are in the wild — how short codes are generated, and whether
the redirect is cacheable — so both were settled and written down before any endpoint
existed. Everything after them was additive.

The three scenarios map onto that sequence:

- **[Greenfield](scenarios/greenfield.md)** — core service, with the two irreversible
  decisions made deliberately.
- **[Brownfield](scenarios/brownfield.md)** — analytics and reliability added to a working
  system, with the blast radius traced before the change. This is where the readiness-probe
  risk was found, which is invisible in a diff.
- **[Ambiguous](scenarios/ambiguous.md)** — "analytics and reliability features" resolved
  into a stated, falsifiable assumption that is surfaced in the API payload itself.

## Artifacts

| Artifact | Location |
| --- | --- |
| Runnable service | `docker compose up --build` |
| Requirements, ambiguity register | [`docs/01-requirements-and-ambiguities.md`](01-requirements-and-ambiguities.md) |
| Architecture, control flow, failure behaviour | [`docs/02-architecture.md`](02-architecture.md) |
| 7 decision records | [`docs/decisions/`](decisions/) |
| Three scenarios | [`docs/scenarios/`](scenarios/) |
| Testing, limitations, trade-offs | [`docs/04-testing-limitations-tradeoffs.md`](04-testing-limitations-tradeoffs.md) |
| AI collaboration log with rejections | [`docs/06-ai-collaboration-log.md`](06-ai-collaboration-log.md) |
| API definition | `/v3/api-docs`, `/swagger-ui.html` |

## The decisions worth defending

**Short codes come from a sequence through a keyed Feistel permutation.** A Feistel network
is a bijection for any round function, which makes distinct codes a structural guarantee
rather than something checked at runtime — no collision read, no retry, no key-pool table.
It is obfuscation, not encryption, and the code says so.

**302, never 301.** A cached 301 permanently ends analytics and makes link retirement
impossible, and it cannot be recalled.

**Analytics are best-effort, and the API admits it.** Every stats response carries an
`accuracyNote`. The alternative — a durable write in the redirect path — trades the
product's core function for metric completeness.

**Optional dependencies fail open and are excluded from health.** A cache incident should
make the service slower, not smaller. Redis in the readiness probe would pull healthy pods
out of rotation and convert degradation into outage.

## Risks and how they are controlled

| Risk | Control |
| --- | --- |
| Redirect path degradation | Analytics cannot block or throw into it; asserted by test |
| Cache outage | Fail-open everywhere; the integration suite runs with Redis dead by default |
| Code enumeration | Keyed permutation, with its limits stated in [ADR-001](decisions/ADR-001-short-code-generation.md) |
| SSRF and open-redirect abuse | Creation-time validation, with the DNS-rebinding gap named and pushed to egress controls |
| Write-path abuse | Token bucket, atomic via Lua |
| Analytics overload | Bounded buffer that sheds load and counts what it sheds |
| Schema drift | Flyway owns the schema; Hibernate validates at startup — which caught a real `CHAR`/`VARCHAR` bug |

## Assumptions

1. The redirect is the product; analytics inform decisions but do not serve users. Where
   they conflict, the redirect wins. *(The load-bearing assumption. Falsifiable in one
   sentence if click data ever feeds billing.)*
2. Click data has no billing or regulatory role.
3. The service runs behind a load balancer that owns `X-Forwarded-For`.
4. Redirects are public by design; link *management* and analytics are access-controlled.
5. Single region, one PostgreSQL, is adequate for the target scale.

## Limitations

Issued tokens cannot be revoked before they expire, so the one-hour TTL is the revocation
window. One shared signing secret. No account self-service. SSRF protection covers literal
IPs only. No malware or phishing feed. Rate limiting fails open. No load test behind the
latency target. `docker compose up --build` could not be run to completion on the
development machine because its `/etc/hosts` blocks Maven Central; the service was verified
against the Compose PostgreSQL and Redis directly instead.

Full detail, including what is *not* tested, is in
[testing, limitations and trade-offs](04-testing-limitations-tradeoffs.md).

## What I would do next, in order

1. **A CI pipeline with dependency scanning and a coverage gate.** Every quality gate here
   was run by hand. That is honest, and it is not repeatable by anyone else — it closes more
   audit gaps than any other single item.
2. **Token revocation**, via a denylist of token identifiers in Redis. Authentication and
   ownership now exist ([ADR-008](decisions/ADR-008-authentication-and-ownership.md)), but
   disabling a user does not stop a token already in flight.
3. **Move analytics to a durable log**, if and only if the answer to "does this feed
   billing" is yes.
4. **Egress network controls**, which is where SSRF is actually stopped.
5. **A retention policy for `click_events`**, once someone answers the legal question.
