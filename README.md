# URL Shortener

Turn a long URL into a short one, redirect visitors to it, and report on the clicks — with the
reliability and abuse controls that make a public redirect endpoint safe to expose.

Built as an AI-assisted engineering exercise. The engineering record — how requirements were
normalized, what was decided and rejected, and how AI was used and overridden — lives in
[`docs/`](docs/) and is as much the deliverable as the code.

![The demo console: a decoded session token, the link workspace, and a live log of every API
call with its status and headers](docs/images/console-full.png)

**Contents**

- [Quick start](#quick-start) · [Using it](#using-it) · [Making links other people can open](#making-links-other-people-can-open)
- [How it works](#how-it-works) · [API](#api) · [Behaviour worth knowing](#behaviour-worth-knowing)
- [Configuration](#configuration) · [Running the tests](#running-the-tests) · [Deploying](#deploying)
- [The engineering record](#the-engineering-record)

## Quick start

Requires Docker. Nothing else — the build runs inside the image.

```bash
docker compose up --build
```

Then open **<http://localhost:8080>**.

| | |
| --- | --- |
| `http://localhost:8080` | Demo console — the guided tour |
| `http://localhost:8080/swagger-ui.html` | Interactive API documentation |
| `http://localhost:8080/actuator/health` | Health check |

Three demo accounts are created on first start: `alice`, `bob` and `admin`, with passwords of
the form `<name>-password`. `admin` additionally sees the audit trail and the blocklist. They
exist only while `SHORTENER_SEED_DEMO_USERS` is `true`, and the application logs a warning on
every startup while it is.

<details>
<summary>If the image build cannot download dependencies</summary>

A build that fails with `Could not transfer artifact ... from/to central` and an address of
`127.0.0.1` means Maven Central is being blackholed locally — usually an `/etc/hosts` entry or
a corporate proxy, not a problem with this repository. Check with
`getent hosts repo.maven.apache.org` (or `grep maven /etc/hosts`).

Either point Maven at a reachable mirror in `~/.m2/settings.xml`, or run the infrastructure in
Docker and the application on the host:

```bash
docker compose up -d postgres redis
SHORTENER_JWT_SECRET=$(openssl rand -base64 48) mvn spring-boot:run
```

</details>

## Using it

### Through the console

The console is the fastest way to understand what the service does, because it shows the API
exchange next to the result. Sign in, shorten something, then use the scenario buttons —
each drives a complete flow and explains what the outcome proves.

| Scenario | What it shows |
| --- | --- |
| Same URL twice | `201` then `200`, same code — retries are safe |
| Hostile destination | `422`, with nothing written and no hint about which check fired |
| Retire, then visit | `404`, never `403` — a `403` would confirm the code exists |
| Destination turns hostile | Redirects fine, then is blocked and swept, and answers `410` |
| Exceed the rate limit | `429` with `Retry-After`, keyed on the account rather than the IP |
| Use a revoked token | `401` on the very next call, long before the token would expire |

**[The full walkthrough](docs/03-demo-walkthrough.md)** covers each one with screenshots.

### Through the API

```bash
# 1. Sign in and keep the token
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alice-password"}' | jq -r .accessToken)

# 2. Shorten a URL
curl -s -X POST http://localhost:8080/api/v1/links \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://en.wikipedia.org/wiki/URL_shortening"}'
# → {"code":"8sh3mqV","shortUrl":"http://localhost:8080/8sh3mqV", ...}

# 3. Open it. No token needed — the redirect is public.
curl -i http://localhost:8080/8sh3mqV
# → HTTP/1.1 302
#   Location: https://en.wikipedia.org/wiki/URL_shortening

# 4. Read the clicks (owner or admin only)
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/links/8sh3mqV/stats
```

Optional fields on creation: `alias` for a code you choose, `expiresAt` for an expiry, and
`forceNew` to mint a second code for a URL you have already shortened.

## Making links other people can open

**The short code is the whole link.** `8sh3mqV` is not a placeholder or an internal
identifier — pasting `http://localhost:8080/8sh3mqV` into a browser really does land on
Wikipedia. That is exactly how every shortener works; `bit.ly/3xY9kLm` has no more meaning in
it than this does.

The code looks random on purpose. It is a keyed permutation of a database sequence rendered in
base62, so codes are unique without a collision check but cannot be walked: seeing `8sh3mqV`
tells you nothing about the next or previous link. A readable or sequential code would let
anyone enumerate every private link in the system. See
[ADR-001](docs/decisions/ADR-001-short-code-generation.md).

**What stops a link working for someone else is the host, not the code.** `localhost` means
*this machine*, so a link built on `http://localhost:8080` only resolves for you. To hand
links to other people, two things have to be true:

1. **The service is reachable at a public address** — a deployed host, or a tunnel such as
   `ngrok http 8080` or `cloudflared tunnel --url http://localhost:8080` for a quick test.
2. **`SHORTENER_BASE_URL` matches that address**, because it is what the API puts in
   `shortUrl`. If it still says `localhost`, the service will keep handing out links that only
   work on your machine even though it is reachable from anywhere.

```bash
SHORTENER_BASE_URL=https://sho.rt docker compose up --build
# → {"code":"8sh3mqV","shortUrl":"https://sho.rt/8sh3mqV", ...}
```

The base URL only affects the link that is *advertised*; resolution works on whatever host
actually receives the request. A short domain is worth buying if the links are going into
places that count characters, but nothing here depends on one.

## How it works

```
                  ┌──────────────┐
  GET /{code} ───▶│   Redirect   │──▶ Redis cache ──▶ PostgreSQL ──▶ 302 Location
   (public)       │  (hot path)  │         │
                  └──────────────┘         └──▶ click buffered in memory, flushed in batches
                                                       │
  POST /api/v1/links ──▶ validate ──▶ screen ──▶ ┌─────▼──────┐
   (authenticated)      destination   blocklist  │ PostgreSQL │
                                                 └────────────┘
```

The redirect is the product, so everything else is arranged to stay out of its way.

- **Codes** come from a database sequence passed through a keyed Feistel permutation and
  encoded in base62. No collision check, no retry loop, and no coordination between instances.
- **Reads** go through a Redis cache whose entry can never outlive the link's own expiry. If
  Redis is down or slow, a circuit breaker trips and the redirect goes straight to PostgreSQL —
  slower, but working.
- **Clicks** are buffered in memory and written in batches about once a second. The redirect
  never waits on an analytics write, and under pressure the buffer sheds load rather than
  queueing until it falls over.
- **Everything else** — creation, screening, statistics, administration — sits off the hot
  path and is allowed to be slower.

The service is stateless, so it scales horizontally behind a load balancer. Full detail in
[`docs/02-architecture.md`](docs/02-architecture.md).

## API

| Method | Path | Access | Purpose |
| --- | --- | --- | --- |
| `GET` | `/{code}` | **Public** | Resolve and redirect. Returns `302`. |
| `GET` | `/` | **Public** | The demo console. |
| `POST` | `/api/v1/auth/token` | Public | Exchange credentials for a bearer token. |
| `POST` | `/api/v1/links` | Authenticated | Create a short link, or return your existing one for the same URL. Optional `alias`, `expiresAt`, `forceNew`. |
| `GET` | `/api/v1/links/{code}` | Owner or admin | Link metadata, without redirecting. |
| `GET` | `/api/v1/links/{code}/stats` | Owner or admin | Click totals, unique visitors, daily series, top referrers. |
| `DELETE` | `/api/v1/links/{code}` | Owner or admin | Retire a link. It stops resolving; its analytics are kept. |
| `POST` | `/api/v1/auth/revoke` | Authenticated | Withdraw the token used to make the request. |
| `POST` | `/api/v1/auth/revoke-all` | Authenticated | Withdraw every token issued to you. |
| `GET` | `/api/v1/admin/audit` | Admin | Read the audit trail, filterable by `action` or `actor`. |
| `GET`/`POST` | `/api/v1/admin/blocked-domains` | Admin | List or add blocked destination hosts. |
| `POST` | `/api/v1/admin/rescan` | Admin | Run the screening sweep now. `?all=true` ignores the age filter. |

The redirect is deliberately public: authenticating it would break every link already shared
with the world. Everything else is default-deny — a path that is not listed above is refused
rather than served.

Errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem documents. The generated
OpenAPI definition is at `/v3/api-docs`.

## Behaviour worth knowing

**The same URL twice gives you the same link.** Submitting a URL you have already shortened
returns the existing one with `200` instead of creating a second with `201`, so a client that
retried after a timeout can tell whether its first attempt landed. Reuse is scoped to your own
links and only applies to plain requests — an alias, an expiry, or `forceNew` always mints a
new code. Matching ignores only what RFC 3986 calls equivalent; a trailing slash is
significant. See [ADR-010](docs/decisions/ADR-010-url-deduplication.md).

**Missing, retired and quarantined links answer differently, on purpose.** A link you do not
own, or one that has been retired, returns `404` rather than `403` — a `403` confirms the code
exists, which is the one thing an enumeration attacker cannot otherwise learn. A link
*quarantined* after its destination turned hostile returns `410` instead, because someone who
followed a link that was later taken down is not the adversary and deserves to know what
happened. See [ADR-007](docs/decisions/ADR-007-expiry-and-retirement.md) and
[ADR-008](docs/decisions/ADR-008-authentication-and-ownership.md).

**Destinations are screened, and re-screened.** A flagged URL is refused with `422` and no
indication of which check caught it, since naming the feed lets someone tune their next
attempt. Nothing is written, so a refusal costs nothing to clean up. Checking once is not
enough — the standard evasion is to shorten a benign page, pass the check, then repoint the
destination — so a background sweep re-screens live links and quarantines any that have turned
hostile. Google Safe Browsing is supported and stays off until `SHORTENER_SAFE_BROWSING_KEY`
is set, so there is no third-party dependency out of the box.

**Abuse gets different responses depending on what it costs to be wrong.** Repeated refused
creations suspend the account and revoke its tokens. Abnormal click rates on a link are
recorded for review but never acted on automatically, because a spike looks identical whether
a campaign is legitimate or not and taking down a real viral link is the more expensive
mistake. Privileged actions are written to an audit trail that the database itself refuses to
let anything update or delete. See
[ADR-011](docs/decisions/ADR-011-security-and-abuse-controls.md).

**No signing key ships with this repository.** A default key in source control is a published
private key. With nothing configured the application generates a random one per process and
warns — tokens then stop working across a restart, which is visible and harmless rather than
silent and exploitable. Prefer `SHORTENER_JWT_SECRET_FILE` pointing at a mounted secret over
`SHORTENER_JWT_SECRET`, since environment variables are readable through `/proc`, crash dumps
and container inspection APIs. Keys rotate without invalidating live tokens.

**It keeps working when Redis does not.** Caching, rate limiting, revocation and abuse
counters all live in Redis, and all of them degrade rather than fail: the service runs without
it, more slowly and with those controls inactive. The one deliberate exception is revocation,
which reports `503` rather than claiming a success it cannot deliver. See
[ADR-009](docs/decisions/ADR-009-circuit-breaking.md).

## Configuration

Every value has a working default for local development. For anything beyond that,
`SHORTENER_CODE_SECRET` and a signing key must be set.

Compose reads `.env` automatically, so the usual route is `cp .env.example .env` and edit —
[`.env.example`](.env.example) lists every setting with the reasoning behind its default, and
`.env` itself is gitignored.

| Variable | Default | Notes |
| --- | --- | --- |
| `APP_PORT` | `8080` | Host port. `POSTGRES_PORT` and `REDIS_PORT` work the same way, for when something is already listening. |
| `SHORTENER_BASE_URL` | `http://localhost:$APP_PORT` | Origin used to build returned short URLs. Follows `APP_PORT` on its own; set it explicitly for a real domain, or shared links will point at `localhost`. |
| `SHORTENER_CODE_SECRET` | development placeholder | Keys the code permutation and the visitor hash. **Change it, then keep it stable** — changing it later changes future codes. |
| `SHORTENER_JWT_SECRET` | *(none)* | Token signing key. Unset generates a random per-process key and warns. Startup fails if it is under 32 bytes. |
| `SHORTENER_JWT_SECRET_FILE` | *(none)* | Path to a mounted secret holding the signing key. Preferred over the variable above. |
| `SHORTENER_SEED_DEMO_USERS` | `true` | Creates the demo accounts. **Set to `false` outside a local demo.** |
| `SHORTENER_SCREENING_ENABLED` | `true` | Destination reputation checking at creation and on rescan. |
| `SHORTENER_SAFE_BROWSING_KEY` | *(none)* | Enables Google Safe Browsing. Without it only the local blocklist runs. |
| `SHORTENER_REVOCATION_FAIL_CLOSED` | `false` | Whether an unreachable revocation store rejects tokens instead of accepting them. |
| `SPRING_DATASOURCE_URL` | local PostgreSQL | |
| `SPRING_DATA_REDIS_HOST` | `localhost` | Optional. See the note on degradation above. |

The demo accounts' passwords are hashed at runtime rather than committed, so no usable
credential lives in this repository.

## Running the tests

```bash
mvn verify
```

137 tests. Unit tests need no infrastructure; the integration suite starts a real PostgreSQL
through Testcontainers and runs the production Flyway migrations against it, so Docker must be
running.

**Use Java 21.** The project targets it, and on newer JDKs Mockito's bytecode backend cannot
create mocks — the unit tests then fail for reasons unrelated to the code. If your default JDK
is newer:

```bash
JAVA_HOME=/path/to/jdk-21 mvn verify
```

[`docs/04-testing-limitations-tradeoffs.md`](docs/04-testing-limitations-tradeoffs.md) covers
what is tested and, more usefully, what is not.

CI runs the same suite on every push, plus a container build with a vulnerability scan and
CodeQL static analysis. See [`.github/workflows/`](.github/workflows/).

## Deploying

The [`Dockerfile`](Dockerfile) is multi-stage and the runtime image is a JRE-only Alpine base
running as a non-root user. `docker compose up` is the local path; anywhere real, the
checklist is short:

1. Set `SHORTENER_BASE_URL` to the public origin.
2. Provide `SHORTENER_JWT_SECRET_FILE` and `SHORTENER_CODE_SECRET` from a secret manager.
3. Set `SHORTENER_SEED_DEMO_USERS=false`.
4. Terminate TLS in front of the service, and keep `server.forward-headers-strategy` set so
   HSTS and the client address survive the proxy.
5. Point `SPRING_DATASOURCE_URL` at managed PostgreSQL and `SPRING_DATA_REDIS_HOST` at managed
   Redis.

`/actuator/health` is public and suitable for liveness and readiness probes; the remaining
actuator endpoints require an administrator token. Prometheus metrics are at
`/actuator/prometheus`.

## The engineering record

Start with the [final engineering summary](docs/05-final-engineering-summary.md); it links
everything else in reading order.

| Document | What is in it |
| --- | --- |
| [Requirements and ambiguities](docs/01-requirements-and-ambiguities.md) | What was asked for, what was ambiguous, and how each gap was resolved |
| [Architecture](docs/02-architecture.md) | Components, data model, request paths, scaling |
| [Demo walkthrough](docs/03-demo-walkthrough.md) | Screenshot tour of every behaviour, and what each status code proves |
| [Testing, limitations, trade-offs](docs/04-testing-limitations-tradeoffs.md) | What is covered, what is not, and what would break first |
| [Final engineering summary](docs/05-final-engineering-summary.md) | Risks, assumptions and limitations in one place |
| [AI collaboration log](docs/06-ai-collaboration-log.md) | Where AI helped, where it was wrong, and what was rejected |
| [Decision records](docs/decisions/) | Twelve ADRs: the decision, the alternatives, and the consequences |
| [Scenarios](docs/scenarios/) | Greenfield, brownfield and ambiguous-requirement worked examples |
