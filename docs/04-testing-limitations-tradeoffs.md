# Testing approach, limitations, and trade-offs

## Testing approach

137 tests, in two tiers, plus a repeatable load and failure drill in `scripts/spike-test.py`.

**Unit tests (63)** run with no infrastructure and target the components where a bug is
silent rather than loud.

- `ShortCodeCodecTest` tests the uniqueness claim as a *property*, not by example:
  round-trip inversion proves the permutation is a bijection, 200,000 consecutive ids
  produce 200,000 distinct codes, and fewer than 5 of 10,000 adjacent id pairs land on
  adjacent outputs. Example-based tests would have passed against a design that collides.
- `UrlValidatorTest` covers 20 hostile inputs across scheme abuse, SSRF, credential
  disguise, and self-reference.
- `ClickRecorderTest` pins failure modes directly: 10,500 events into a 10,000-slot buffer
  sheds exactly 500 and reports it; a repository that throws abandons the batch rather than
  propagating into the redirect path.

`ErrorContractIntegrationTest` covers malformed requests — bad JSON, wrong method, wrong
content type, non-numeric parameters — every one of which reported `500` until the handler
was corrected. It also asserts that error bodies never contain a package or class name, so
the contract cannot start leaking internals unnoticed.

- `JwtKeyRingTest` and `TokenRevocationTest` concentrate on absent or broken configuration and
  on the Redis-outage path, because those are the branches nobody exercises deliberately and
  both have security consequences. The fail-open behaviour is asserted explicitly: it is a
  deliberate weakening, and a deliberate weakening should fail loudly if someone reverses it
  by accident.
- `AbuseMonitorTest` pins the asymmetry between the two abuse signals — refused creations
  suspend an account, click spikes only raise a flag — so neither can be quietly collapsed
  into the other.

**Integration tests (74)** run the whole application against real PostgreSQL with the
production Flyway migrations. They cover the API contract end to end, including the
degraded paths.

`SecurityControlsIntegrationTest` drives screening, quarantine and the audit trail through the
API rather than the service layer, so it also covers the wiring. It asserts that a refused
destination writes nothing at all, that the refusal names no check, that a link whose
destination turns hostile is quarantined by the sweep and then answers `410`, and — because
Redis is absent in this profile — that a revocation request answers `503` rather than claiming
a success it cannot deliver.

The audit trail's immutability is tested against the database, not the application: the test
issues raw `UPDATE` and `DELETE` and asserts both are refused. Testing it through the
repository would only prove that the code does not currently try.

`SecurityIntegrationTest` is deliberately weighted towards the negative cases, because
anything can be made to work for an authorized caller. It asserts that anonymous and
tampered-token requests are refused, that the two credential-failure responses are
*byte-identical* so the login endpoint cannot enumerate usernames, that one user cannot read
or retire another's link, that ownerless links are administrator-only, and that the public
redirect stays public.

Four of its cases cover the demo console, and none of them check that it looks right. They
assert that its files are served anonymously, that an unlisted file in the same directory is
still refused — which is what would fail if someone replaced the individual permits with a
`/**` wildcard — that a console filename still falls outside the short-code pattern, and that
the page is served under the strict CSP with `script-src 'self'` and no `unsafe-inline`. That
last one fails the day the console needs the policy weakened, which is the point at which
someone should have to justify it.

Two choices there are worth defending:

**Redis is deliberately absent.** The test profile points it at a dead port, so every API
test also proves the service behaves identically without its cache and rate limiter. The
degraded path is the one most likely to occur in production and the least likely to be
tested; making it the default removes that gap.

**The scheduled flush is disabled and driven explicitly.** A timer racing assertions
produces exactly the intermittent failure that teaches a team to re-run the suite instead of
reading it. The analytics test asserts the asynchrony itself — after three redirects, stats
read zero and the buffer holds three — which is the contract the redirect path depends on.

### Running them

```bash
mvn test
```

The suite uses a database supplied by `TEST_DATASOURCE_URL` if present, and starts a
Testcontainers PostgreSQL otherwise. Reusing an already-running database (CI service
container, or the local Compose stack) avoids paying container startup on every run.

## Manual verification against a running instance

Some properties cannot be asserted from inside the test suite, because they require killing
a dependency. These were exercised by hand against the live service, and the results are
recorded here rather than assumed.

**Dependency failure drills.** Each dependency was stopped in turn:

| Condition | Cached redirect | Uncached redirect | Create |
| --- | --- | --- | --- |
| PostgreSQL stopped | `302` in ~6 ms | `500` | `500` |
| Redis stopped | `302` in ~6 ms | `302` | `201` |
| Both healthy | `302` in ~3 ms | `302` | `201` |

The first run of this drill **failed** and exposed a real defect — see the note in
[architecture](02-architecture.md#verified-not-assumed). `ShortLinkResolutionTest` now guards
the regression.

**Concurrency.** Driven with `xargs -P` against the running service:

| Check | Result |
| --- | --- |
| 2,000 redirects, 100 in parallel | 2,000 × `302`, zero errors |
| 200 creations, 50 in parallel, distinct API keys | 200 × `201` in under 500 ms |
| Code uniqueness across 455 links | 455 rows, 455 distinct codes, 455 distinct ids — zero collisions |
| 25 simultaneous claims of one alias | exactly 1 × `201`, 24 × `409` |
| 40 rapid creations on one API key (burst 20) | exactly 20 × `201`, 20 × `429` |

The alias result confirms the unique index is the real arbiter under contention, and the
rate-limit result confirms the Lua script is genuinely atomic — a non-atomic
read-modify-write would have let more than 20 through.

## What is not tested, stated plainly

- **No load or latency test.** The p99 target in NFR-1 is a design intent. The concurrency
  runs above show the service handles parallel traffic without errors, but they were driven
  by a shell loop on the same machine and measure the harness as much as the server. They
  are a smoke check, not a benchmark.
- **No cache-coherence test under concurrent write and read.** Eviction ordering is reasoned
  about, not proven.
- **The dependency drills are manual.** They should be an automated resilience suite that
  pauses containers; today they rely on someone running the commands.
- **No mutation testing**, so the tests' own sensitivity is unmeasured.
- **The Dockerfile build is not exercised by the test suite.** `docker compose up --build`
  was not run to completion on the development machine, because its `/etc/hosts` blackholes
  Maven Central; the application was verified end to end running against the Compose
  PostgreSQL and Redis instead. This is the deliverable's weakest verification link and it
  is called out rather than assumed.

## Limitations

| Limitation | Consequence | Why it is acceptable here |
| --- | --- | --- |
| No CI pipeline, dependency scanning, or coverage gate | Every quality gate was run by hand; a known CVE in a transitive dependency would go unnoticed | The single largest gap. Honest, but not repeatable by anyone else |
| Deduplication is by URL, not by request | Two genuinely distinct requests sharing a URL look identical to a retry | Closed the retry-duplicate case ([ADR-010](decisions/ADR-010-url-deduplication.md)); `forceNew` is the escape hatch. An `Idempotency-Key` header remains the more general answer |
| Matching is conservative | A trailing slash or reordered query parameters produce a second link | Deliberate asymmetry: a duplicate row is cheap, a wrong redirect is not |
| No rollback path, and migrations are forward-only | Rolling back past a migration is not currently safe | Worth stating regardless of whether a pipeline exists |
| Logs are plain text, and there is no tracing | Correlating a slow redirect across instances is manual | Metrics are exported; structured JSON logs and tracing are the next observability step |
| Circuit breaker counts consecutive failures, not a sliding window | A dependency failing intermittently at 40% will not trip it | The mode it defends against — a hung server — fails every call, not some ([ADR-009](decisions/ADR-009-circuit-breaking.md)) |
| Breaker state is not exported as a metric | An operator sees transitions in logs but cannot alert on them cleanly | Should be a gauge before this runs anywhere real |
| No rate limit on the redirect path | A distributed flood of valid codes is unthrottled | Redirects are the product; negative caching already blunts scanners, and this belongs at the edge |
| Revocation fails open | A Redis outage suspends revocation, so a withdrawn token keeps working until it expires | Failing closed turns a cache outage into an authentication outage; the short TTL is the guarantee that always holds, and `revocation-fail-closed` inverts it ([ADR-011](decisions/ADR-011-security-and-abuse-controls.md)) |
| No user self-registration or password rotation | Accounts are seeded or inserted directly | Account lifecycle is a product surface of its own, not a shortener feature |
| Signing keys are symmetric, even though they now rotate | Every verifier needs the secret, so a compromise anywhere forges tokens everywhere | Rotation and `kid` selection are in place ([ADR-011](decisions/ADR-011-security-and-abuse-controls.md)); asymmetric keys with a JWKS endpoint are the fix once there is more than one verifier |
| SSRF defence is literal-IP only | A hostname resolving to a private address is not caught | DNS rebinding defeats resolve-time checks; egress rules are the real control ([ADR-006](decisions/ADR-006-url-safety.md)) |
| Screening ships with an empty blocklist and no feed | Out of the box it refuses nothing until a domain is added or a Safe Browsing key is configured | The mechanism, the rescan sweep and the operator controls are the hard part and are complete; the data is a subscription decision ([ADR-011](decisions/ADR-011-security-and-abuse-controls.md)) |
| The rescan sweep runs on every instance | Duplicated work once horizontally scaled | Batches are capped and re-screening is idempotent, so it is wasteful rather than wrong; needs a lock or a dedicated worker before scale-out |
| `audit_events` grows without bound | Same storage problem as `click_events`, and more pressing | Retention here is a legal question before it is a technical one |
| Analytics are lossy | Buffered events lost on kill; dropped under overload | Deliberate ([ADR-004](decisions/ADR-004-async-analytics.md)), surfaced in every stats response |
| Rate limiting fails open | A Redis outage means unlimited creation | Wrong default for a paid API; flagged in [ADR-005](decisions/ADR-005-rate-limiting.md) with the one-method fix |
| Single-region, single database | No horizontal read scaling, no failover story | Correct for a prototype; the seams for change are identified in [architecture](02-architecture.md) |
| Code space is 2^40 | ~1.1 trillion links, then exhaustion | Fails loudly with a clear message rather than silently colliding |
| `click_events` grows without bound | Storage cost, slower aggregates over time | Needs a retention answer that is legal, not technical |

## Trade-offs, and what each one bought

| Trade-off | Given up | Bought |
| --- | --- | --- |
| 302 over 301 | Cheap cacheable redirects | Complete analytics; working retirement; changeable destinations |
| Async best-effort analytics | Guaranteed click capture | A redirect path that cannot be slowed or broken by analytics |
| Feistel permutation over a key pool | Cryptographic-strength unguessability | Zero collisions with no extra table, no claim protocol, no refill job |
| Redis optional | Enforced rate limits during an outage | Redirects that survive a cache failure |
| Append-only events over a counter column | A single cheap `SELECT` for totals | No row-lock contention on exactly the links that matter most |
| Lazy expiry over a sweeper | A tidy table | No window where an expired link still resolves |
| PostgreSQL over a dedicated KV store | Headroom at ten-billion scale | Transactions, SQL aggregates, and operational familiarity now |
