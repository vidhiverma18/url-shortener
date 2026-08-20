# Final engineering summary

## What was built

A URL shortening service: create links, redirect visitors, report click analytics. It runs on
`docker compose up --build`, exposes an OpenAPI-documented REST API, and passes 137 tests
covering the code generation guarantees, hostile input, the analytics failure modes, the
authentication and abuse controls, and the full API contract against a real PostgreSQL.

Three layers were added after the core service, each in its own pass: authentication with
ownership, then the security and abuse controls (destination screening, quarantine, token
revocation, key rotation, an append-only audit trail), then a demo console that makes the
behaviour visible.

Verified end to end on a running instance: redirects return 302 with the correct headers and
serve from cache in ~3 ms; analytics aggregate by day and referrer; SSRF targets are refused
with an RFC 9457 problem document; a link whose destination turns hostile is quarantined by
the sweep and answers 410; a revoked token is refused on the next request. The
[demo walkthrough](03-demo-walkthrough.md) shows each of these with screenshots taken from a
live instance.

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
| Demo console | `http://localhost:8080` |
| Requirements, ambiguity register | [`docs/01-requirements-and-ambiguities.md`](01-requirements-and-ambiguities.md) |
| Architecture, control flow, failure behaviour | [`docs/02-architecture.md`](02-architecture.md) |
| Screenshot walkthrough of every behaviour | [`docs/03-demo-walkthrough.md`](03-demo-walkthrough.md) |
| 12 decision records | [`docs/decisions/`](decisions/) |
| Three scenarios | [`docs/scenarios/`](scenarios/) |
| Testing, limitations, trade-offs | [`docs/04-testing-limitations-tradeoffs.md`](04-testing-limitations-tradeoffs.md) |
| AI collaboration log with rejections | [`docs/06-ai-collaboration-log.md`](06-ai-collaboration-log.md) |
| CI: build, test, image scan, static analysis | [`.github/workflows/`](../.github/workflows/) |
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

**Three different "gone" responses, chosen by who is asking.** A link you do not own returns
`404`, because `403` would confirm the code exists to whoever is scanning. A retired link also
returns `404`, for the same reason. A *quarantined* link returns `410`, because the person
following it received it in good faith and the destination was withdrawn afterwards. The same
underlying state produces different answers depending on which mistake is more expensive.

**Abuse responses are asymmetric on purpose.** Repeated refused creations suspend an account;
abnormal click velocity is only recorded. A click spike looks identical whether a campaign is
legitimate or not, and taking down a real viral link costs more than watching a fake one.

## Risks and how they are controlled

| Risk | Control |
| --- | --- |
| Redirect path degradation | Analytics cannot block or throw into it; asserted by test |
| Cache outage | Fail-open everywhere; the integration suite runs with Redis dead by default |
| Code enumeration | Keyed permutation, with its limits stated in [ADR-001](decisions/ADR-001-short-code-generation.md) |
| SSRF and open-redirect abuse | Creation-time validation, with the DNS-rebinding gap named and pushed to egress controls |
| Write-path abuse | Token bucket, atomic via Lua, keyed on the account rather than the address |
| Analytics overload | Bounded buffer that sheds load and counts what it sheds |
| Schema drift | Flyway owns the schema; Hibernate validates at startup — which caught a real `CHAR`/`VARCHAR` bug |
| Malicious destinations | Blocklist screening at creation, plus a rescan sweep that quarantines links whose destination turns hostile |
| Stolen or leaked token | Per-token and per-principal revocation, checked on every request |
| Signing key compromise | No key in source control; rotation via `kid` without invalidating live tokens |
| Tampering with the record | `audit_events` is append-only, enforced by database triggers rather than by the application |

## Assumptions

1. The redirect is the product; analytics inform decisions but do not serve users. Where
   they conflict, the redirect wins. *(The load-bearing assumption. Falsifiable in one
   sentence if click data ever feeds billing.)*
2. Click data has no billing or regulatory role.
3. The service runs behind a load balancer that owns `X-Forwarded-For`.
4. Redirects are public by design; link *management* and analytics are access-controlled.
5. Single region, one PostgreSQL, is adequate for the target scale.

## Limitations

**Still true.** SSRF protection covers literal IPs and known metadata hostnames, not DNS
rebinding — that belongs to egress controls. Rate limiting, caching and abuse counters all
fail open when Redis is unavailable, which is deliberate and does mean those controls are
inactive during an outage. No account self-service, and nothing in the product lifts an
account suspension. No load test behind the latency target. Analytics are best-effort and can
drop clicks under sustained pressure. Single region, single PostgreSQL. There is no "list my
links" endpoint, which the console works around with browser storage.

**No longer true, and worth noting because earlier drafts of this document said otherwise.**
Tokens are now revocable per token and per principal; signing keys rotate through a `kid`
without invalidating live tokens; destinations are screened against a blocklist at creation
and re-screened by a sweep, with Google Safe Browsing available behind a key; and a CI
pipeline now runs the tests, a container vulnerability scan and CodeQL rather than relying on
gates run by hand.

**Environment.** `docker compose up --build` could not be run to completion on the development
machine, because its `/etc/hosts` blackholes Maven Central and the image build cannot fetch
dependencies. The service was verified by running it against the Compose PostgreSQL and Redis
directly, and the CI image job exists partly so that the container build is proven somewhere
that has normal network access.

Full detail, including what is *not* tested, is in
[testing, limitations and trade-offs](04-testing-limitations-tradeoffs.md).

## What I would do next, in order

1. **A coverage gate in CI.** The pipeline now runs tests, an image scan and CodeQL, but
   nothing yet fails a build for untested code. Coverage is the one quality gate still
   enforced only by judgement.
2. **A "list my links" endpoint.** The most visible product gap: the console has to keep its
   own list in browser storage because the API cannot answer "what have I created", which also
   means there is no way to find a link again from another machine.
3. **An operator path out of an account suspension.** The abuse monitor can disable an account
   and nothing in the product re-enables it. Today that needs SQL, which is not an answer.
4. **Move analytics to a durable log**, if and only if the answer to "does this feed billing"
   is yes.
5. **Egress network controls**, which is where SSRF is actually stopped.
6. **A retention policy for `click_events`**, once someone answers the legal question.
