package com.example.shortener.security;

import com.example.shortener.config.ShortenerProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKMatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Key resolution and rotation.
 *
 * <p>The behaviour worth pinning down is what happens when configuration is absent or wrong,
 * because those are the paths nobody exercises deliberately and both have security
 * consequences: a weak key must stop the process, and a missing one must not fall back to
 * something an attacker could also know.
 */
class JwtKeyRingTest {

    private static final String STRONG = "a-signing-key-that-is-at-least-32-bytes";
    private static final String ALSO_STRONG = "another-signing-key-of-sufficient-length";

    @Test
    @DisplayName("every configured key can verify, and the first one signs")
    void rotationKeepsOldKeysForVerification() {
        ShortenerProperties properties = propertiesWithKeys(
                key("2026-08", ALSO_STRONG),
                key("2026-05", STRONG));

        JwtKeyRing ring = new JwtKeyRing(properties);

        assertThat(ring.size()).isEqualTo(2);
        // The newest key signs; the outgoing one stays only to verify tokens already issued,
        // which is what makes a rotation invisible to clients holding live tokens.
        assertThat(ring.activeKeyId()).isEqualTo("2026-08");
        assertThat(selectable(ring, "2026-05")).as("outgoing key still verifies").isTrue();
    }

    @Test
    @DisplayName("a key shorter than HS256 requires stops startup")
    void weakKeysAreRefused() {
        ShortenerProperties properties = new ShortenerProperties();
        properties.getSecurity().setJwtSecret("too-short");

        assertThatThrownBy(() -> new JwtKeyRing(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HS256 requires at least 32");
    }

    @Test
    @DisplayName("duplicate key ids are refused rather than resolved by ordering")
    void duplicateKeyIdsAreRefused() {
        ShortenerProperties properties = propertiesWithKeys(
                key("same", STRONG),
                key("same", ALSO_STRONG));

        assertThatThrownBy(() -> new JwtKeyRing(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate JWT key id");
    }

    @Test
    @DisplayName("a secret file is read, and takes precedence over an inline value")
    void secretFileWins(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("jwt.key");
        // Trailing newline included deliberately: every secret manager and shell redirect adds
        // one, and a key that silently differs by one byte from what the operator set is the
        // kind of failure that costs an afternoon.
        Files.writeString(file, ALSO_STRONG + "\n");

        ShortenerProperties properties = new ShortenerProperties();
        properties.getSecurity().setJwtSecret(STRONG);
        properties.getSecurity().setJwtSecretFile(file.toString());

        JwtKeyRing ring = new JwtKeyRing(properties);

        assertThat(ring.size()).isEqualTo(1);
        assertThat(ring.activeKeyId()).isEqualTo("primary");
    }

    @Test
    @DisplayName("an unreadable secret file stops startup instead of falling back")
    void missingSecretFileIsFatal() {
        ShortenerProperties properties = new ShortenerProperties();
        properties.getSecurity().setJwtSecretFile("/nonexistent/jwt.key");

        // Falling back to the inline value or to a generated key would mean a typo in the
        // mount path silently downgrades the deployment to a key nobody chose.
        assertThatThrownBy(() -> new JwtKeyRing(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot read signing key file");
    }

    @Test
    @DisplayName("with nothing configured a key is generated rather than defaulted")
    void absentConfigurationGeneratesAKey() {
        JwtKeyRing first = new JwtKeyRing(new ShortenerProperties());
        JwtKeyRing second = new JwtKeyRing(new ShortenerProperties());

        assertThat(first.size()).isEqualTo(1);
        assertThat(first.activeKeyId()).isEqualTo("ephemeral");
        // Distinct per process. A shipped constant would be identical everywhere, which is
        // the whole problem: anyone with the source could mint tokens against any deployment
        // that never overrode it.
        assertThat(keyMaterial(first)).isNotEqualTo(keyMaterial(second));
    }

    private ShortenerProperties propertiesWithKeys(ShortenerProperties.JwtKey... keys) {
        ShortenerProperties properties = new ShortenerProperties();
        properties.getSecurity().setJwtKeys(List.of(keys));
        return properties;
    }

    private ShortenerProperties.JwtKey key(String id, String secret) {
        ShortenerProperties.JwtKey key = new ShortenerProperties.JwtKey();
        key.setId(id);
        key.setSecret(secret);
        return key;
    }

    private boolean selectable(JwtKeyRing ring, String keyId) {
        try {
            return !ring.jwkSource()
                    .get(new JWKSelector(new JWKMatcher.Builder()
                            .keyID(keyId)
                            .algorithm(JWSAlgorithm.HS256)
                            .build()), null)
                    .isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private String keyMaterial(JwtKeyRing ring) {
        try {
            return ring.jwkSource()
                    .get(new JWKSelector(new JWKMatcher.Builder().build()), null)
                    .get(0)
                    .toJSONString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
