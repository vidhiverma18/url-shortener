# ADR-011: Destination screening, token withdrawal, and an audit trail

- **Status:** Accepted
- **Date:** 2026-08-19
- **Closes:** the eight security-and-abuse gaps recorded in the design-gap review
- **Affects:** `SecurityConfig`, `JwtKeyRing`, `TokenRevocationService`, `AuditLog`,
  `service.screening.*`, `AbuseMonitor`, `AdminController`, `V5` migration

## Context

Authentication ([ADR-008](ADR-008-authentication-and-ownership.md)) established *who* is
calling. It said nothing about *what* they are allowed to point at, whether a token can be
withdrawn before it expires, or whether anyone could reconstruct afterwards what an
administrator did. A URL shortener is an anonymity layer over an arbitrary destination, which
makes it attractive infrastructure for phishing; the destination is the part that matters and
it was entirely unexamined.

Eight gaps were addressed together because they interlock. Screening produces refusals, which
are the signal abuse monitoring acts on, which produces suspensions, which are only meaningful
if tokens can be revoked, and all of it is worthless without a record.

## Decisions

### 1. No signing key ships with the code

The previous default, `local-development-signing-key-change-me-please-32b`, was in the
repository. That is not a placeholder, it is a published private key: anyone with the source
could mint an `ADMIN` token against any deployment that never overrode it.

It is now absent. With nothing configured the application generates a random key per process
and logs a warning. That fails **visibly** — tokens stop working across a restart and between
instances — rather than silently and exploitably. `jwt-secret-file` was added and is preferred
over the environment variable, because env vars leak through `/proc`, crash dumps, child
processes and container inspection APIs. A key shorter than 32 bytes still refuses to start;
an unreadable secret file is fatal rather than falling back, since a typo in a mount path must
not quietly downgrade a deployment to a key nobody chose.

### 2. Keys rotate through a `kid`-addressed ring

Tokens carry a `kid` header, and every configured key verifies while only the first signs.
Rotation is: add a key at the front, wait one token lifetime, remove the last. Tokens signed
by the outgoing key keep working until they expire naturally, so rotation is invisible to
clients.

Verification uses a Nimbus `JWSVerificationKeySelector` over a `JWKSource`, which still pins
HS256 and so retains the algorithm-confusion protection the previous single-key decoder had.

### 3. Revocation exists, and fails open by default

Tokens carry a `jti`. Two withdrawal mechanisms, because they answer different questions:
revoking a `jti` handles "this token leaked"; revoking everything issued to a principal before
a cut-off handles "this account is compromised", which cannot be done by enumeration because
the service never recorded what it issued. Entries expire with the tokens they describe, so
the list stays proportional to tokens in flight.

**With Redis unreachable, tokens are accepted and revocation is suspended.** This is a real
weakening and it is deliberate: failing closed converts a cache outage into a total
authentication outage, which for this service is the worse failure. The mitigation is the
short token lifetime — the expiry, not the list, is the guarantee that always holds.
`revocation-fail-closed` inverts the choice for deployments that disagree.

The revoke endpoint answers **503, not 200**, when it cannot record the revocation. Reporting
success would tell someone their leaked token is dead while it is still valid, which is worse
than reporting the failure.

### 4. Destinations are screened, and screened again

`UrlReputationChecker` is the one genuine port in the codebase, and it earns the indirection:
a local blocklist and a third-party threat feed are operationally nothing alike. Any checker
saying blocked blocks — reputation is not a vote, since one credible malware report outweighs
any number of sources that have not heard of the domain.

Screening runs after structural validation and before an id is allocated, so a refusal costs
no sequence value, no row and no code. The response is **422** with no detail about which
check caught it; naming the feed would turn screening into an oracle for tuning the next
attempt.

**Screening at creation is necessary and not sufficient.** The standard evasion is to shorten
a benign page, pass the check, then repoint or compromise the destination — the short code
never changes and a create-time check never looks again. A scheduled sweep re-screens live
links, oldest first with never-screened ones at the head, and quarantines what has turned
hostile. That sweep is also what makes fail-open defensible: a link admitted while a provider
was unreachable is re-examined within one interval rather than never.

Quarantine is distinct from an owner retiring a link. The redirect answers **410** with an
explanation, because the visitor who followed a link that has since been taken down is not the
adversary, and telling them so is the difference between a warning and an apparently broken
site. The verdict is cached at full TTL — these are the links most likely to be under load,
and evicting alone would send every visitor to the database to be told the same thing.

The blocklist has two sources: configuration for the static baseline, and a table an operator
can add to mid-incident. Blocking a domain is a minutes-scale decision and a deployment
pipeline is not a minutes-scale tool.

### 5. Abuse gets two different responses

Rate limiting answers "how fast". Abuse monitoring answers "doing what" — three requests a
minute is inside every limit, and if all three are known-malicious destinations that is not a
slow client, it is someone probing the blocklist.

- **Repeated refused creations suspend the account automatically.** There is no innocent
  reason to keep submitting flagged destinations, so the false-positive cost is low. Suspension
  also revokes the account's tokens, because disabling an account otherwise only blocks future
  logins and leaves live tokens working through exactly the window that matters.
- **Unusual click velocity is only recorded.** A spike looks identical whether the campaign is
  legitimate or not, and taking down a real viral link is the more expensive mistake.
  Automation that cannot tell them apart should escalate, not act.

Velocity is computed during the analytics flush rather than in the redirect, so watching for
abuse costs the hot path nothing.

### 6. The audit trail is append-only in the database

`audit_events` rejects `UPDATE` and `DELETE` through a trigger that raises. Enforcement lives
in the database rather than in application code because application code can be changed by
whoever is covering their tracks. A rule with `DO INSTEAD NOTHING` was rejected: it discards
the write silently, so a caller believes it succeeded.

Each write runs in its own transaction, so a denial survives the rollback of the request that
caused it — an audit trail retaining only successful actions is precisely backwards.

Failed logins record the username that was tried, because reconstructing a credential-stuffing
run afterwards is impossible without it, and never whether that username exists.

### 7. Response headers

HSTS, a `default-src 'none'` CSP, `frame-ancestors 'none'`, `Referrer-Policy`,
`Permissions-Policy` and frame denial. `frame-ancestors` is the load-bearing one: the redirect
is the endpoint an attacker would want to frame, to harvest the click without the address bar.

`script-src 'self'` and `style-src 'unsafe-inline'` exist only so Swagger UI renders. Without
them the documentation page is blank, which is the usual reason a strict policy gets weakened
later in a hurry and much further than necessary.

`server.forward-headers-strategy: framework` is part of this decision, not incidental. Spring
suppresses HSTS on non-secure requests, so behind a TLS-terminating proxy the header would be
configured, look correct in code, and never be sent.

## Consequences

- **Screening adds a call to the creation path.** Tight timeouts, a dedicated circuit breaker
  and fail-open keep a slow provider from becoming our latency. The breaker is separate from
  the Redis one so an outage at Google cannot disable the local cache.
- **The rescan sweep is single-instance work run on every instance.** Harmless at this scale —
  the batch is capped and re-screening a link twice is idempotent — but it needs a lock or a
  dedicated worker before horizontal scale.
- **`audit_events` grows without bound**, like `click_events`. Retention and partitioning
  remain open, and are more pressing here because the trail is legally interesting.
- **Auto-suspension can be weaponised** if an attacker can cause refusals attributable to
  another account. They currently cannot: the counter keys on the authenticated principal, and
  causing refusals on someone else's account requires their token. Any future unauthenticated
  or delegated creation path would break that assumption.
- **Two tests were found to be unsound while adding these.** One tampered with a JWT by
  flipping the final base64url character of the signature — which encodes only 4 significant
  bits, so the decoded signature was often byte-identical and a "forged" token verified
  correctly. It now alters a character with full significance and asserts the decoded bytes
  actually differ.
