package com.example.shortener.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The correctness of the whole write path rests on one claim: distinct ids produce
 * distinct codes, always, without a database check. These tests exist to hold that
 * claim rather than to cover lines.
 */
class ShortCodeCodecTest {

    private final FeistelPermutation permutation = new FeistelPermutation("test-secret");

    @Test
    @DisplayName("permutation is invertible, which is what guarantees it is a bijection")
    void permutationRoundTrips() {
        long[] samples = {0, 1, 2, 61, 62, 12_345, 1L << 20, (1L << 39) + 7, FeistelPermutation.DOMAIN_SIZE - 1};
        for (long value : samples) {
            assertThat(permutation.invert(permutation.apply(value)))
                    .as("round trip of %d", value)
                    .isEqualTo(value);
        }
    }

    @Test
    @DisplayName("no collisions across a dense block of consecutive ids")
    void permutationIsCollisionFreeOverConsecutiveIds() {
        Set<Long> seen = new HashSet<>();
        for (long id = 0; id < 200_000; id++) {
            assertThat(seen.add(permutation.apply(id)))
                    .as("collision at id %d", id)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("consecutive ids do not produce consecutive codes")
    void permutationDestroysSequentiality() {
        // The point of the permutation is that an attacker holding one code cannot
        // guess its neighbours. If adjacent ids landed on adjacent outputs the whole
        // mechanism would be decorative.
        long adjacentOutputs = 0;
        for (long id = 0; id < 10_000; id++) {
            if (Math.abs(permutation.apply(id + 1) - permutation.apply(id)) <= 1) {
                adjacentOutputs++;
            }
        }
        assertThat(adjacentOutputs).isLessThan(5);
    }

    @Test
    @DisplayName("every permuted value fits in seven base62 characters")
    void permutedValuesFitTheCodeWidth() {
        for (long id = 0; id < 50_000; id++) {
            String code = Base62.encode(permutation.apply(id), 7);
            assertThat(code).hasSize(7).matches("[0-9A-Za-z]{7}");
        }
        // Boundary: the largest value the domain can produce must still encode.
        assertThat(Base62.encode(FeistelPermutation.DOMAIN_SIZE - 1, 7)).hasSize(7);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 61, 62, 3843, 238_327, 1_000_000_000L})
    void base62RoundTrips(long value) {
        assertThat(Base62.decode(Base62.encode(value, 7))).isEqualTo(value);
    }

    @Test
    void base62RejectsValuesWiderThanTheRequestedWidth() {
        assertThatThrownBy(() -> Base62.encode(Long.MAX_VALUE, 7))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void permutationRejectsValuesOutsideItsDomain() {
        assertThatThrownBy(() -> permutation.apply(FeistelPermutation.DOMAIN_SIZE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> permutation.apply(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a different secret yields a different code space")
    void secretChangesTheMapping() {
        FeistelPermutation other = new FeistelPermutation("a-different-secret");
        long differing = 0;
        for (long id = 0; id < 1_000; id++) {
            if (permutation.apply(id) != other.apply(id)) {
                differing++;
            }
        }
        assertThat(differing).isGreaterThan(990);
    }
}
