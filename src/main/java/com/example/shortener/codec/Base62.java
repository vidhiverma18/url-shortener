package com.example.shortener.codec;

/**
 * Base62 codec over {@code [0-9A-Za-z]}.
 *
 * <p>Base62 rather than Base64 because the output travels in a URL path and must
 * survive copy/paste out of chat clients and email: Base64's {@code +} and {@code /}
 * require escaping, and {@code -}/{@code _} of the URL-safe variant are easy to
 * confuse with hyphenation when a link wraps across lines.
 */
public final class Base62 {

    private static final char[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final int BASE = 62;

    private Base62() {
    }

    /**
     * Encodes a non-negative value, left-padded with the zero digit to exactly
     * {@code width} characters. Fixed width keeps every short code the same length,
     * which makes the public URL space uniform and avoids leaking the age of a link
     * through its length.
     */
    public static String encode(long value, int width) {
        if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative");
        }
        char[] out = new char[width];
        long remaining = value;
        for (int i = width - 1; i >= 0; i--) {
            out[i] = ALPHABET[(int) (remaining % BASE)];
            remaining /= BASE;
        }
        if (remaining != 0) {
            throw new IllegalArgumentException("value does not fit in " + width + " base62 digits");
        }
        return new String(out);
    }

    public static long decode(String encoded) {
        long value = 0;
        for (int i = 0; i < encoded.length(); i++) {
            int digit = digitOf(encoded.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("not a base62 character: " + encoded.charAt(i));
            }
            value = value * BASE + digit;
        }
        return value;
    }

    private static int digitOf(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'A' && c <= 'Z') {
            return c - 'A' + 10;
        }
        if (c >= 'a' && c <= 'z') {
            return c - 'a' + 36;
        }
        return -1;
    }
}
