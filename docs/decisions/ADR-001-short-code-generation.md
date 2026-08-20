# ADR-001: Derive short codes from a sequence through a keyed permutation

- **Status:** Accepted
- **Date:** 2026-08-19
- **Affects:** `ShortCodeFactory`, `FeistelPermutation`, `Base62`, `IdAllocator`

## Context

Every short link needs a unique 7-character code. Uniqueness has to hold under concurrency,
and the code must not let anyone enumerate the corpus by counting.

## Options considered

**Hash the URL and truncate.** MD5 or SHA-256 of the long URL, first 7 Base62 characters.
Requires a read before every write to detect collisions, and collides anyway: at 42 bits of
output the birthday bound bites well before the billions of rows this design should
tolerate. It also makes the same URL always produce the same code, which silently merges
two campaigns' analytics — see A-3 in the [ambiguity register](../01-requirements-and-ambiguities.md).

**Random codes with a uniqueness check.** Unguessable, but every write becomes read-check-
retry, and the retry rate degrades as the space fills.

**Sequence, Base62-encoded directly.** Collision-free and cheap, but the codes are
consecutive. Anyone with one link can walk every link ever created. Disqualifying.

**Pre-generated key pool.** A service mints random codes in advance into an unused-keys
table. Genuinely good at scale, and what several production shorteners use. It costs a
second table, a claim protocol that must be atomic, and a refill job — three moving parts
to solve a problem the option below solves with none.

## Decision

Allocate a monotonic id from a PostgreSQL sequence, pass it through a 4-round Feistel
network keyed by a deployment secret, and Base62-encode the result to exactly 7 characters.

A Feistel network is a bijection for *any* round function. That single property gives
uniqueness for free: distinct ids cannot produce the same code, so there is no collision
check, no retry loop, and no second table. The permutation is what breaks sequentiality.

The domain is 2^40 (~1.1 trillion codes) rather than 2^42 because the output must fit in
7 Base62 characters and 62^7 ≈ 3.52 trillion sits between the two. 2^40 is the largest
power of two with even halves that fits.

## Consequences

- One `nextval` per creation. Fine at this scale; `IdAllocator` is a separate component so
  a Snowflake or range allocator can replace it without touching the service layer.
- **This is obfuscation, not encryption.** Four rounds of a non-cryptographic round function
  stop casual enumeration, not a cryptanalyst with many known id/code pairs. Short codes are
  treated as unguessable handles, never as authorization. Anything sensitive behind a link
  needs real access control.
- Rotating `SHORTENER_CODE_SECRET` does not break existing links — codes are persisted, not
  recomputed — but it does change the mapping for codes minted afterwards.
- The bijection claim is load-bearing, so it is tested directly: round-trip inversion,
  collision-freedom across 200,000 consecutive ids, and a check that adjacent ids do not
  produce adjacent codes.
