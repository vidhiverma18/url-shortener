# Testing approach, limitations, and trade-offs

## Testing approach

55 tests, in two tiers.

**Unit tests (42)** run with no infrastructure and target the components where a bug is
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

**Integration tests (13)** run the whole application against real PostgreSQL with the
production Flyway migrations. They cover the API contract end to end, including the
degraded paths.

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
| No authentication | Anyone can create links; anyone with a code can read its analytics | A-4. A shallow auth story would be worse than an honest gap |
| SSRF defence is literal-IP only | A hostname resolving to a private address is not caught | DNS rebinding defeats resolve-time checks; egress rules are the real control ([ADR-006](decisions/ADR-006-url-safety.md)) |
| No malware or phishing feed | A benign domain that turns hostile keeps resolving | Needs a live reputation service and an async re-check pipeline |
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
