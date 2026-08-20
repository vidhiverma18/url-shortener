# Scenario 1 — Greenfield: build the core service

**Requirement as given:** "Build a URL shortener service from scratch with core APIs."

Nothing existed. The risk in greenfield work is not difficulty, it is that the wrong
foundation gets poured quickly and cheerfully, and everything after it inherits the mistake.

## Decomposition

Ordered by dependency, not by size. The first two tasks are the ones that are expensive to
reverse, so they were settled before any endpoint was written.

| # | Task | Depends on | Why it sits here |
| --- | --- | --- | --- |
| 1 | Decide code generation strategy | — | Determines uniqueness guarantees, enumerability, and whether writes need a read. Reversing it later invalidates every code already issued. |
| 2 | Decide redirect semantics (301 vs 302) | — | Near-irreversible: a cached 301 cannot be recalled. |
| 3 | Schema and migrations | 1 | Column widths and indexes follow from the code shape |
| 4 | Domain model and repositories | 3 | |
| 5 | URL validation | — | Gate on the only dangerous input; must exist before the create endpoint is reachable |
| 6 | Create and redirect endpoints | 1, 4, 5 | |
| 7 | Error contract | 6 | |
| 8 | Tests for the uniqueness claim | 1, 6 | The claim the whole write path rests on |

## Execution

**Acceptance criteria set before writing code.** Codes are 7 characters of `[0-9A-Za-z]`;
distinct ids always produce distinct codes with no database check; consecutive creations do
not produce adjacent codes; the redirect is a 302 that no cache may store; invalid or unsafe
URLs are refused with an actionable 400.

**Where AI was used and where it was overridden** is recorded in full in the
[AI collaboration log](../06-ai-collaboration-log.md). The two that mattered here:

- The first generated design was *hash the URL, truncate to 7 characters, retry on
  collision*. It is the most common answer because it is the most common blog post.
  **Rejected**: it needs a read before every write, it still collides at 42 bits of output,
  and it makes the same URL always yield the same code, which silently merges two
  campaigns' analytics into one bucket. Replaced with sequence → Feistel permutation →
  Base62, which is collision-free by construction. [ADR-001](../decisions/ADR-001-short-code-generation.md).
- The generated redirect used `301` with a comment about it being faster. **Rejected**: it
  is faster precisely because it stops reaching the service, which ends analytics and makes
  retirement impossible. [ADR-002](../decisions/ADR-002-redirect-status.md).

## Validation

The uniqueness claim is load-bearing, so it is tested as a property rather than by example:
round-trip inversion proves the permutation is a bijection; 200,000 consecutive ids produce
200,000 distinct codes; fewer than 5 of 10,000 adjacent id pairs produce adjacent outputs;
every permuted value in the domain fits in 7 characters, including the boundary value.

`UrlValidatorTest` covers 20 hostile inputs across four attack classes — scheme abuse,
SSRF, credential disguise, and self-reference.

## Outcome

A working create-and-redirect service with 42 unit tests, an RFC 9457 error contract, and
two of the three decisions that are expensive to reverse made deliberately and written down.
