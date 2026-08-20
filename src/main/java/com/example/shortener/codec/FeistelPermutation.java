package com.example.shortener.codec;

import java.nio.charset.StandardCharsets;

/**
 * A keyed, format-preserving permutation of the {@code [0, 2^40)} integer range,
 * built as a 4-round balanced Feistel network over two 20-bit halves.
 *
 * <p>Why this exists: short codes are minted from a monotonic database sequence, so
 * the raw ids are consecutive. Encoding them directly would let anyone walk the
 * entire link corpus by incrementing a code. A Feistel network is a bijection for
 * <em>any</em> round function, so scrambling the id preserves the one-code-per-id
 * guarantee (no collisions, ever, with no collision-check read) while making the
 * output sequence unpredictable without the key.
 *
 * <p>This is obfuscation, not encryption. Four rounds of a non-cryptographic round
 * function resist casual enumeration, not a determined cryptanalyst who can harvest
 * many known id/code pairs. Short codes are treated as unguessable handles, never as
 * an access-control mechanism; anything sensitive needs real authorization on top.
 *
 * <p>The domain is 2^40 (~1.1 trillion) rather than 2^42 because the output must fit
 * in 7 Base62 characters, and 62^7 (~3.52 trillion) sits between the two. 2^40 is the
 * largest power of two with even halves that fits.
 */
public final class FeistelPermutation {

    public static final int DOMAIN_BITS = 40;
    private static final int HALF_BITS = DOMAIN_BITS / 2;
    private static final int HALF_MASK = (1 << HALF_BITS) - 1;
    private static final int ROUNDS = 4;
    public static final long DOMAIN_SIZE = 1L << DOMAIN_BITS;

    private final long key;

    public FeistelPermutation(String secret) {
        this.key = mix(hash(secret));
    }

    public long apply(long value) {
        requireInDomain(value);
        int left = (int) ((value >>> HALF_BITS) & HALF_MASK);
        int right = (int) (value & HALF_MASK);
        for (int round = 0; round < ROUNDS; round++) {
            int nextLeft = right;
            int nextRight = left ^ roundFunction(right, round);
            left = nextLeft;
            right = nextRight;
        }
        return ((long) left << HALF_BITS) | right;
    }

    /** Inverse permutation. Not needed in the request path; retained so tests can prove bijectivity. */
    public long invert(long value) {
        requireInDomain(value);
        int left = (int) ((value >>> HALF_BITS) & HALF_MASK);
        int right = (int) (value & HALF_MASK);
        for (int round = ROUNDS - 1; round >= 0; round--) {
            int previousRight = left;
            int previousLeft = right ^ roundFunction(previousRight, round);
            left = previousLeft;
            right = previousRight;
        }
        return ((long) left << HALF_BITS) | right;
    }

    private int roundFunction(int half, int round) {
        return (int) (mix((half & 0xFFFFFFFFL) ^ (key + round * 0x9E3779B97F4A7C15L)) & HALF_MASK);
    }

    private static void requireInDomain(long value) {
        if (value < 0 || value >= DOMAIN_SIZE) {
            throw new IllegalArgumentException("value outside 2^" + DOMAIN_BITS + " domain: " + value);
        }
    }

    /** SplitMix64 finalizer: cheap, strong avalanche, no external dependency. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static long hash(String secret) {
        long h = 0xCBF29CE484222325L;
        for (byte b : secret.getBytes(StandardCharsets.UTF_8)) {
            h = (h ^ (b & 0xFF)) * 0x100000001B3L;
        }
        return h;
    }
}
