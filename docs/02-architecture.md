# Architecture

## Shape of the system

A single Spring Boot service in front of PostgreSQL, with Redis as an optional
accelerator. Two paths run through it, and they have opposite priorities.

```
                    ┌──────────────────────────────────────────────┐
  POST /api/v1/links│  WRITE PATH — correctness over latency        │
  ───────────────►  │  RateLimiter ─► UrlValidator ─► IdAllocator   │
                    │       │              │              │         │
                    │    (Redis)      (reject unsafe)  (sequence)   │
                    │                                     ▼         │
                    │                            ShortCodeFactory   │
                    │                        Feistel ─► Base62 (7)  │
                    │                                     ▼         │
                    │                              PostgreSQL       │
                    └──────────────────────────────────────────────┘

                    ┌──────────────────────────────────────────────┐
  GET /{code}       │  READ PATH — latency and availability first   │
  ───────────────►  │  LinkCache ──hit──────────────────► 302       │
                    │      │ miss                          ▲        │
                    │      ▼                               │        │
                    │  PostgreSQL ──► populate cache ──────┘        │
                    │      │                                        │
                    │      └─► ClickRecorder (in-memory buffer)     │
                    │                 │ every ~1s, batched          │
                    │                 ▼                             │
                    │           click_events                        │
                    └──────────────────────────────────────────────┘
```

The dashed boundary that matters most: **nothing in the analytics flow can block, fail, or
slow the redirect.** `ClickRecorder.record` is a non-blocking offer onto a bounded queue.
If the queue is full the event is dropped and counted. If the database is down the batch is
abandoned and logged. In neither case does the visitor notice.

## Components

| Component | Responsibility | Notable property |
| --- | --- | --- |
| `RedirectController` | The hot path. Resolve, emit 302, fire an analytics event. | Path pattern is constrained to the code charset so it cannot shadow `/api` or `/actuator` |
| `LinkController` | Link lifecycle and stats | Rate limited; returns RFC 9457 problems |
| `ShortLinkService` | Ordering rules the controllers must not be trusted with | Validate before allocate; persist before cache; evict after write |
| `ShortCodeFactory` | id → public code, and alias vetting | Collision-free by construction |
| `FeistelPermutation` | Keyed bijection over `[0, 2^40)` | Makes sequential ids non-enumerable |
| `UrlValidator` | The only dangerous input | Blocks non-HTTP schemes, private ranges, metadata endpoints, embedded credentials, self-reference |
| `LinkCache` | Read-through cache | Fails open; caches misses; TTL capped at link expiry |
| `RateLimiter` | Token bucket for creation | Atomic via Lua; fails open |
| `ClickRecorder` | Buffered, batched analytics writes | Sheds load rather than blocking |
| `VisitorHasher` | Pseudonymous visitor id | Daily-rotating salt, so it cannot track across days |
| `SecurityConfig` | Stateless JWT filter chain | Default-deny; the redirect is the one public route |
| `AuthController` / `JwtIssuer` | Credentials → signed bearer token | Brute-force limited; generic failures |

## The trust boundary

The system has two audiences and they need opposite treatment. **Anyone on the internet**
follows a short link; **an authenticated owner** manages links and reads analytics. So
`GET /{code}` is public and everything else is default-deny.

Authentication is a signed JWT verified by Spring Security's resource-server filter, with no
session and no per-request database read. Authorization is scoped to ownership rather than
role alone: `short_links.created_by` carries the authenticated principal, and a non-owner is
answered `404` rather than `403` so the response cannot confirm that a code exists.
Administrators bypass ownership; links predating authentication have no owner and are
administrator-only. Reasoning in
[ADR-008](decisions/ADR-008-authentication-and-ownership.md).

The security filter chain sits *in front of* the rate limiter, which is why creation can now
be throttled per principal instead of per client address.

## Control flow, in the order that matters

**Creating a link.** Rate limit, then validate, *then* allocate an id. Validating before
allocating means a typo does not consume a sequence value — otherwise the id space becomes
a public record of how often users mistype URLs. The code is derived from the id by a
keyed permutation and Base62 encoding, so it is unique without a collision check. The
unique index on `code` is the final arbiter for custom aliases; a `DataIntegrityViolation`
becomes a `409`, not a `500`.

**Resolving a link.** Cache first. A cached miss short-circuits to 404, which is what stops
a code scanner from turning into a database load generator. A cached hit is served without
re-checking expiry, which is only safe because the cache TTL is capped at the link's
remaining lifetime when it is written. On a miss, one indexed read, then populate.

## Data model

`short_links` is a narrow table with a unique index on `code` that doubles as the redirect
lookup index. It deliberately has **no click counter column**: a counter would serialise
every redirect for a popular link behind one row lock, which is the classic way this design
falls over.

`click_events` is append-only, indexed on `(short_link_id, occurred_at DESC)`. It stores the
referring *host* rather than the full referrer, because referrer paths and queries routinely
carry session tokens, and a salted daily hash rather than the visitor's IP.

Schema is owned by Flyway. Hibernate runs with `ddl-auto: validate`, so a drifted entity
fails at startup instead of at 3am.

## Failure behaviour

| Dependency fails | What happens | Why |
| --- | --- | --- |
| Redis unreachable | Redirects still work, served from PostgreSQL. Rate limiting stops enforcing. | Availability of the redirect path outranks enforcement. [ADR-005](decisions/ADR-005-rate-limiting.md) records why this is the wrong default for a paid API. |
| PostgreSQL unreachable | **Cached codes still redirect** (302 in ~6 ms). Uncached codes and creation fail with 500. Health reports DOWN. | Existing hot links keep working, which is the property users actually depend on. |
| Analytics table unwritable | Redirects unaffected; events dropped and counted. | NFR-6, stated as a hard rule. |
| Process killed | Buffered events lost (bounded by ~1s of traffic). | Accepted. [ADR-004](decisions/ADR-004-async-analytics.md). |

Redis is excluded from the health indicator on purpose. A soft dependency that flips the
readiness probe would take healthy pods out of rotation during a cache incident, converting
a degradation into an outage.

PostgreSQL is *not* excluded, so a database outage marks the instance DOWN. That is the
right call for creation and for uncached reads, and the wrong one for the cached redirects
that survive — an orchestrator will pull a pod that is still serving traffic correctly.
Splitting liveness from readiness, so a database outage stops new traffic being routed for
writes while cached redirects keep serving, is the next change here and is left explicit
rather than silently accepted.

### Verified, not assumed

The table above was checked by stopping each dependency against a running instance. The
first attempt failed: with PostgreSQL stopped, a cached redirect returned 500 after a
3-second stall, because `resolve` was annotated `@Transactional(readOnly = true)` and Spring
opened a transaction — taking a pool connection — before the cache was ever consulted. The
annotation is gone and the drill now behaves as documented. The episode is why
`ShortLinkResolutionTest` exists.

## What would change at scale

This runs comfortably at the scale a single Postgres can serve. The three things that would
change first, in order:

1. **Analytics move to a durable log.** The in-process buffer becomes Kafka with a separate
   consumer writing to a columnar store. This is the seam most likely to be crossed first
   and the code is arranged so it is a `ClickRecorder` swap.
2. **Id allocation stops touching the database.** `IdAllocator` exists as its own component
   precisely so a Snowflake generator or a batched range allocator drops in behind it.
3. **A CDN absorbs the hottest codes** with a short TTL, trading a slice of analytics
   fidelity for origin load. That is a product decision, not an engineering one, because it
   knowingly loses clicks.
