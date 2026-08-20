# ADR-012: Ship the demo console as zero-build static assets served by the application

- **Status:** Accepted
- **Date:** 2026-08-19
- **Affects:** `src/main/resources/static/`, `SecurityConfig`, `DemoUserSeeder`, `AdminController.rescan`

## Context

The service is hard to evaluate by looking at it. Almost every decision worth reviewing is
expressed as a status code or a header — `200` versus `201` for a repeated URL, `404` versus
`410` for a link that is gone, `422` for a refused destination, `Retry-After` on a rate-limit
refusal, the response headers that carry the security posture. A conventional front end
renders a short link in every one of those cases and shows none of it.

A reviewer therefore has two bad options: read the tests, or drive the API by hand with curl
and know in advance which responses matter.

## Decision

**The console is a request inspector with controls attached**, not a link manager with a
request log bolted on. Every call it makes is listed with its status, timing, request body
and the response headers that carried a decision, and six scenario buttons drive multi-step
flows end to end and state in prose what the outcome means. The interesting thing on screen
is the exchange, not the short link.

**It is plain HTML, CSS and JavaScript with no build step and no CDN.** The Content Security
Policy is `default-src 'none'; script-src 'self'` with no `unsafe-inline`. A bundled
framework or a CDN import would have required relaxing it, and a policy that gets relaxed to
accommodate a demo surface is a policy that will get relaxed again. The console is built to
fit the existing policy; not one directive changed. It also avoids adding a Node toolchain to
a Docker build that already cannot reach Maven Central in this environment.

**It is served by the application rather than as a separate origin.** Same origin means no
CORS configuration to add — and no CORS configuration is a security property, not a
convenience. It also means `connect-src 'self'` is sufficient.

**Its files are permitted individually, not by a wildcard.** `/`, `/index.html`,
`/console.js`, `/console.css` and `/favicon.svg` are named explicitly so that `anyRequest()`
stays `denyAll()`. A `/**` rule would publish anything that later lands in the static
directory; a test asserts that an unlisted file there is still refused.

**Everything is rendered with `textContent`, never `innerHTML`.** Destinations are
attacker-controlled by definition in a link shortener. Building markup from them would put a
DOM XSS hole in the console of a service whose entire purpose is handling hostile URLs safely.

Two supporting changes were needed to make behaviour demonstrable rather than merely present:

- **`POST /api/v1/admin/rescan`**, with `?all=true` to ignore the age filter. The scheduled
  sweep only examines links screened longer ago than the rescan interval, so the moment an
  operator most wants a sweep — just after blocking a domain — is exactly when it would skip
  the links that matter. This is operational tooling that the console happens to need.
- **A seeded blocked domain** (`malware-demo.example`, reserved by RFC 2606) behind the same
  flag as the demo accounts. The blocklist ships empty, so without it the screening demo has
  nothing to refuse and screening looks switched off.

## Consequences

- The short-code pattern `[A-Za-z0-9_-]{3,32}` owns the root namespace, so the console cannot
  have client-side routes: a path like `/dashboard` would be resolved as a link and answered
  `404`. Every asset name contains a dot, which is outside that character class and is the
  only reason `/console.js` reaches the static handler at all. A test pins this, because it is
  the kind of constraint that is invisible until someone adds a route and cannot work out why
  it 404s.
- `HEAD /` is refused, because the permits are `GET`-only. This matches the redirect endpoint,
  which has always behaved the same way, and browsers issue `GET` for pages.
- The console keeps its link list in browser storage, because the API has no "list my links"
  endpoint. That gap is real; surfacing it in the UI copy is preferable to hiding it behind a
  client-side cache that looks like a server feature.
- The seeder also re-enables demo accounts that have suspended themselves. Running the
  screening scenario five times in an hour crosses the abuse threshold, and the account is then
  locked with no in-product way back — which turns a correct security control into a demo that
  works once. Restarting the application is now the reset. It only ever touches accounts the
  seeder owns, and only when demo seeding is enabled.
- The seeder now refreshes the blocklist snapshot after writing. The checker loads its
  snapshot on a fixed delay starting at zero, which races the `ApplicationRunner`; without the
  explicit refresh the seeded entry could be invisible for a full refresh interval, and a demo
  that only works sixty seconds after startup reads as a broken one.
- Without a build step there is no minification, no bundling and no TypeScript. At the size of
  one page that is a fair trade; if the console grows into a real product surface, this ADR is
  the thing to revisit first.
