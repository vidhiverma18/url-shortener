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
# Create a short link
curl -s -X POST http://localhost:8080/api/v1/links \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/a/very/long/path?utm_source=newsletter"}'

# Follow it (-i shows the 302 and the Location header)
curl -i http://localhost:8080/<code>

# Read the analytics
curl -s http://localhost:8080/api/v1/links/<code>/stats
```

## API

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/v1/links` | Create a short link. Optional `alias` and `expiresAt`. |
| `GET` | `/{code}` | Resolve and redirect. Returns `302`. |
| `GET` | `/api/v1/links/{code}` | Link metadata, without redirecting. |
| `GET` | `/api/v1/links/{code}/stats` | Click totals, unique visitors, daily series, top referrers. |
| `DELETE` | `/api/v1/links/{code}` | Retire a link. It stops resolving; its analytics are kept. |

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
| `SPRING_DATASOURCE_URL` | local PostgreSQL | |
| `SPRING_DATA_REDIS_HOST` | `localhost` | Optional. The service runs without it, more slowly. |

## Where to read next

Start with the [final engineering summary](docs/05-final-engineering-summary.md); it links
everything else in reading order.

- [Requirements and ambiguities](docs/01-requirements-and-ambiguities.md)
- [Architecture](docs/02-architecture.md)
- [Scenarios: greenfield, brownfield, ambiguous](docs/scenarios/)
- [Decision records](docs/decisions/)
- [Testing, limitations, trade-offs](docs/04-testing-limitations-tradeoffs.md)
- [AI collaboration log](docs/06-ai-collaboration-log.md)
