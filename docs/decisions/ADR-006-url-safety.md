# ADR-006: Validate destinations at creation, and do not pretend to prevent SSRF

- **Status:** Accepted
- **Date:** 2026-08-19
- **Affects:** `UrlValidator`

## Context

A URL shortener is an open redirector by design. That makes it attractive for two things:
laundering phishing links behind a trusted-looking domain, and probing networks that the
attacker cannot reach but the service can.

## Decision

Validate once, at creation time, and reject:

| Rejected | Why |
| --- | --- |
| Schemes other than `http`/`https` | `javascript:` and `data:` turn a short link into stored XSS against any client that follows it; `file:` targets the local filesystem |
| Embedded credentials | `https://paypal.com@evil.example` is a disguise, not a credential |
| Loopback, private, link-local, multicast literals | The SSRF pivot into internal networks |
| `169.254.169.254`, `metadata.google.internal` and friends | The highest-value SSRF target: cloud instance credentials |
| `.internal`, `.local`, `.localdomain`, `localhost` | Same class, reachable by name in many environments |
| This service's own host | Redirect loops and chains |
| Anything over 2048 characters | Bounds the storage and the log lines |

Validation happens at creation rather than on each redirect, so the read path stays a pure
key lookup.

## Consequences

- **This does not prevent SSRF, and the code says so.** The validator deliberately does not
  resolve DNS. A resolve-then-allow check is defeated by DNS rebinding and by records that
  change between creation and the first redirect, so it would buy confidence without buying
  safety. Claiming otherwise would be worse than the gap itself.
- The real control is at the network layer: egress rules that stop the service reaching
  private ranges regardless of what a URL says. That belongs in deployment, and it is
  called out as a limitation rather than quietly assumed.
- A domain that is benign at creation and hostile a week later still resolves. Defending
  that needs a live reputation feed (Google Safe Browsing or equivalent) checked
  asynchronously, with the link retired on a hit. Out of scope, and listed in
  [limitations](../04-testing-limitations-tradeoffs.md).
- Rejections are specific enough for a caller to fix their request and vague enough not to
  confirm which internal hosts exist: every blocked-host case returns the same
  "not publicly routable" message.
