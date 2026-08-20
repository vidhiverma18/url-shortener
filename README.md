# URL Shortener

A production-shaped URL shortening service: create short links, redirect visitors, and
report click analytics, with the reliability and abuse-control behaviour that makes the
redirect path safe to expose publicly.

Built as an AI-assisted engineering exercise. The engineering record — how requirements
were normalized, how work was decomposed, what was decided and rejected, and how AI was
used and overridden — lives in [`docs/`](docs/) and is as much the deliverable as the code.

## Quick start

Requires Docker. Nothing else.

```bash
docker compose up --build
```

The API comes up on `http://localhost:8080`. Interactive docs are at
`http://localhost:8080/swagger-ui.html`; health is at `/actuator/health`.

```bash
# Get a token (demo accounts: alice, bob, admin — passwords are <name>-password)
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alice-password"}' | jq -r .accessToken)

# Create a short link
curl -s -X POST http://localhost:8080/api/v1/links \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/a/very/long/path?utm_source=newsletter"}'

# Follow it — no token needed, the redirect is public (-i shows the 302)
curl -i http://localhost:8080/<code>

# Read the analytics — owner or admin only
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/links/<code>/stats
```

## API

| Method | Path | Access | Purpose |
| --- | --- | --- | --- |
| `POST` | `/api/v1/auth/token` | Public | Exchange credentials for a bearer token. |
| `GET` | `/{code}` | **Public** | Resolve and redirect. Returns `302`. |
| `POST` | `/api/v1/links` | Authenticated | Create a short link. Optional `alias` and `expiresAt`. |
| `GET` | `/api/v1/links/{code}` | Owner or admin | Link metadata, without redirecting. |
| `GET` | `/api/v1/links/{code}/stats` | Owner or admin | Click totals, unique visitors, daily series, top referrers. |
| `DELETE` | `/api/v1/links/{code}` | Owner or admin | Retire a link. It stops resolving; its analytics are kept. |

The redirect is deliberately public: authenticating it would break every link already
shared with the world. Everything else is default-deny.

A caller who is not the owner receives `404`, not `403`. A `403` would confirm that the
code exists, which is the one thing an enumeration attacker cannot otherwise learn. See
[ADR-008](docs/decisions/ADR-008-authentication-and-ownership.md).

Errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem documents. The full
OpenAPI definition is generated at `/v3/api-docs`.

## Running the tests

```bash
mvn test
```

Unit tests run with no infrastructure. The integration suite starts a real PostgreSQL
container through Testcontainers and runs the production Flyway migrations against it, so
Docker must be running. See [`docs/04-testing-limitations-tradeoffs.md`](docs/04-testing-limitations-tradeoffs.md)
for what is covered and, more usefully, what is not.

## Configuration

Every value has a working default for local development. In any real deployment,
`SHORTENER_CODE_SECRET` must be set to a generated secret and kept stable.

| Variable | Default | Notes |
| --- | --- | --- |
| `SHORTENER_BASE_URL` | `http://localhost:8080` | Origin used to build returned short URLs. |
| `SHORTENER_CODE_SECRET` | development placeholder | Keys the code permutation and the visitor hash. **Change it.** |
| `SHORTENER_JWT_SECRET` | development placeholder | Token signing key. **Change it.** Startup fails if it is under 32 bytes. |
| `SHORTENER_SEED_DEMO_USERS` | `true` | Creates the demo accounts above. **Set to `false` outside a local demo.** |
| `SPRING_DATASOURCE_URL` | local PostgreSQL | |
| `SPRING_DATA_REDIS_HOST` | `localhost` | Optional. The service runs without it, more slowly. |

The demo accounts exist so a reviewer can exercise the API in one command. They are created
by a seeder that logs a warning on every startup, and their passwords are hashed at runtime
rather than committed, so no usable credential lives in this repository.

## Where to read next

Start with the [final engineering summary](docs/05-final-engineering-summary.md); it links
everything else in reading order.

- [Requirements and ambiguities](docs/01-requirements-and-ambiguities.md)
- [Architecture](docs/02-architecture.md)
- [Scenarios: greenfield, brownfield, ambiguous](docs/scenarios/)
- [Decision records](docs/decisions/)
- [Testing, limitations, trade-offs](docs/04-testing-limitations-tradeoffs.md)
- [AI collaboration log](docs/06-ai-collaboration-log.md)
