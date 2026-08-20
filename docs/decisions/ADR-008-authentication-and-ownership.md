# ADR-008: JWT bearer authentication with ownership-scoped authorization

- **Status:** Accepted
- **Date:** 2026-08-19
- **Supersedes:** assumption A-4 in the [ambiguity register](../01-requirements-and-ambiguities.md), which deferred authentication
- **Affects:** `SecurityConfig`, `AuthController`, `JwtIssuer`, `ShortLinkService`, `V3` migration

## Context

The prototype had no authentication. Anyone could create links, and — worse — anyone
holding a short code could read that link's full analytics, including referrer breakdown
and visitor counts. That was recorded as a known limitation rather than defended.

## Options considered

**API keys hashed at rest.** Simple, stateless, revocable by deleting a row. Every request
costs a BCrypt verification unless a cache is added, and there is no standard client story.

**HTTP Basic.** Fastest to build. Sends reusable credentials on every request and has no
expiry, so a leaked header is a permanent compromise.

**OAuth2 resource server against an external identity provider.** The realistic enterprise
answer. Nothing runs end to end without an IdP, so the demo would be theatre.

**JWT bearer tokens, signed with an HMAC secret, issued by this service.** Chosen.
Stateless verification with no per-request database read, a standard `Authorization: Bearer`
client story, and a built-in expiry.

## Decision

`POST /api/v1/auth/token` exchanges a username and password for an HS256-signed JWT
carrying `sub` and a `roles` claim, valid for one hour.

Access rules, default-deny:

| Path | Access |
| --- | --- |
| `GET /{code}` | **Public.** Non-negotiable — the redirect is the product |
| `POST /api/v1/auth/token` | Public, rate limited by client address |
| `/actuator/health` | Public |
| `/actuator/**` | `ROLE_ADMIN` |
| `/api/**` | Authenticated |
| anything else | Denied |

Authorization is **ownership-scoped**, not merely role-based. `short_links.created_by`
now records the authenticated principal, and reading, retiring or reading analytics for a
link requires being its owner or an administrator.

Verification uses Spring Security's resource-server support rather than hand-rolled JWT
parsing, and the MAC algorithm is pinned. Signature verification, algorithm confusion and
claim validation are precisely where bespoke JWT code goes wrong.

## Consequences

- **A non-owner gets 404, not 403.** A 403 confirms that the code exists, which is the one
  bit an enumeration attacker cannot otherwise obtain. This matches the reasoning for
  returning 404 rather than 410 on expired links ([ADR-007](ADR-007-expiry-and-retirement.md)).
- **Links created before this change have no owner and are administrator-only.** They are
  not adopted by the first caller who asks for them.
- **Rate limiting improved as a side effect.** Creation is now keyed by the authenticated
  principal instead of the client address, which closes the bypass noted in
  [ADR-005](ADR-005-rate-limiting.md): an address-keyed bucket is defeated by a pool of IPs
  and over-restricts everyone behind shared NAT.
- **Tokens cannot be revoked before they expire.** This is the price of stateless
  verification, and the one-hour TTL *is* the revocation window. Disabling a user stops new
  tokens but does not invalidate one already issued. A denylist of token identifiers in
  Redis would fix it, at the cost of a lookup per request — worth doing when there is a real
  account-compromise story to answer.
- **The signing secret is a single point of compromise.** It is validated to be at least
  256 bits and the application refuses to start with a weaker one, because a short key
  degrades every token silently rather than failing loudly.
- **CSRF protection is disabled.** Correct here and not a shortcut: there is no cookie and
  no session, so there is no ambient authority for a forged cross-site request to ride on.
  If a cookie-based flow is ever added, this must be revisited first.
- **Demo accounts are seeded behind a flag** that logs a warning on every startup. Passwords
  are hashed at runtime rather than committed as literals, so no usable credential is in the
  repository.
