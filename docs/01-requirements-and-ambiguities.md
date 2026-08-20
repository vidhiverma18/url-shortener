# Requirements and ambiguities

## The brief, as given

> Build a URL shortener service from scratch with core APIs, analytics, and reliability
> features.

Three of those words carry no definition: *core*, *analytics*, and *reliability*. The
first job was to turn them into statements that can be built against and argued with,
rather than to start typing.

## Normalized requirements

**Functional**

| ID | Requirement | Priority |
| --- | --- | --- |
| FR-1 | Create a short link from a long URL and return it immediately | Must |
| FR-2 | Resolve a short code to its destination and redirect the visitor | Must |
| FR-3 | Accept a caller-chosen alias, rejecting collisions and reserved words | Should |
| FR-4 | Accept an optional expiry, after which the link stops resolving | Should |
| FR-5 | Retire a link on request without destroying its history | Should |
| FR-6 | Report per-link click analytics: total, unique, recent, by day, by referrer | Must |

**Non-functional**

| ID | Requirement | Target | Why this number |
| --- | --- | --- | --- |
| NFR-1 | Redirect latency | p99 under 50 ms server-side | Below human perception; the redirect is an invisible hop and must stay that way |
| NFR-2 | Redirect availability | Higher than creation availability | Every existing link in the world breaks when redirects fail; nobody notices a creation outage for a minute |
| NFR-3 | Codes are not enumerable | Cannot walk the corpus by incrementing | Links are shared privately and treated as semi-secret, whatever the docs say |
| NFR-4 | The service cannot be used to attack the internal network | No private-range or metadata targets | A shortener is an open redirector by design |
| NFR-5 | Creation is rate limited per client | Configurable, default 60/min | Unbounded writes are the cheapest way to hurt this service |
| NFR-6 | Analytics failure never affects redirects | Redirect path independent of analytics writes | Stated as a hard rule, not a preference |

## Ambiguity register

Each of these was a genuine fork in the road. Every one is resolved with a stated
assumption, and every assumption is falsifiable by a product owner in one sentence.

| # | Ambiguity | Interpretations considered | Resolution | Reversal cost |
| --- | --- | --- | --- | --- |
| A-1 | What does "analytics" mean? | (a) a click counter; (b) per-click detail with dimensions; (c) a real-time dashboard | **(b)** — dimensioned click events with aggregate queries. (a) is too thin to be worth calling analytics; (c) is a product, not a service feature. See [the ambiguous scenario](scenarios/ambiguous.md). | Low. (a) is a subset; (c) reads from the same table. |
| A-2 | What does "reliability" mean? | (a) uptime SLO; (b) graceful degradation of dependencies; (c) durable analytics | **(b)** for the redirect path, explicitly **not** (c). Analytics are best-effort. | Medium. Durability needs a real queue. |
| A-3 | Is the same long URL deduplicated to one code? | (a) yes, save space; (b) no, one code per creation | **(b)** — dedup silently merges two campaigns' analytics into one bucket, which is a data-correctness bug wearing a storage-optimisation costume. | Low, additive. |
| A-4 | Who owns a link? | (a) anonymous; (b) API-key scoped; (c) full user accounts | Originally **(a)**, with the owner recorded for future use. **Revised to (c)** after the reviewer asked for restricted access: JWT bearer tokens with per-user ownership on every management endpoint. The redirect stays public. See [ADR-008](decisions/ADR-008-authentication-and-ownership.md). | Resolved. The `created_by` column anticipated this and now carries the authenticated principal. |
| A-5 | Do expired links return 404 or 410? | (a) 404; (b) 410 Gone | **(a)** — distinguishing "expired" from "never existed" tells a scanner which codes were once real. | Trivial. |
| A-6 | 301 or 302? | (a) 301, cacheable, fast; (b) 302, always hits origin | **(b)** — a 301 is cached by browsers indefinitely, which permanently ends both analytics and the ability to retire a link. See [ADR-002](decisions/ADR-002-redirect-status.md). | High if wrong. Already-issued 301s cannot be recalled. |

## Out of scope, and stated plainly

Multi-tenancy beyond per-user ownership, custom domains, malware and phishing scanning against a
live feed, geographic analytics, a web UI, and multi-region replication. Each is a
defensible product requirement; none can be done credibly in the time available, and a
shallow version of any of them would be worse than its absence.
