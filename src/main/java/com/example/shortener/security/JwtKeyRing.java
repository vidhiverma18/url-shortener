package com.example.shortener.security;

import com.example.shortener.config.ShortenerProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * The set of keys that can verify a token, and the one that signs new ones.
 *
 * <p>Rotation works because verification accepts every configured key while issuance uses
 * only the first. Adding a key at the front and removing the last one after the maximum token
 * lifetime has elapsed rotates the signing key without invalidating tokens already in flight.
 * Each key carries a {@code kid} that goes into the token header, so verification selects
 * directly rather than trying keys in turn.
 *
 * <p>When nothing is configured, a random key is generated for this process. That is
 * deliberately not the same as shipping a default: a default signing key in a source
 * repository is a published private key, and anyone holding it can mint an administrator
 * token against every deployment that never overrode it. A generated key fails visibly
 * instead — tokens stop working across a restart or between instances, which is noticed
 * immediately and exploitable by nobody.
 */
@Component
public class JwtKeyRing {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyRing.class);

    /** HS256 is defined over a 256-bit key; anything shorter silently weakens every token. */
    private static final int MINIMUM_SECRET_BYTES = 32;

    private static final String GENERATED_KEY_ID = "ephemeral";

    private final List<OctetSequenceKey> keys;
    private final String activeKeyId;
    private final JWKSource<SecurityContext> jwkSource;

    public JwtKeyRing(ShortenerProperties properties) {
        this.keys = resolve(properties.getSecurity());
        this.activeKeyId = keys.get(0).getKeyID();
        this.jwkSource = new ImmutableJWKSet<>(new JWKSet(new ArrayList<JWK>(keys)));

        if (keys.size() > 1) {
            log.info("JWT key ring loaded with {} keys; signing with '{}'", keys.size(), activeKeyId);
        }
    }

    public JWKSource<SecurityContext> jwkSource() {
        return jwkSource;
    }

    public String activeKeyId() {
        return activeKeyId;
    }

    public int size() {
        return keys.size();
    }

    private List<OctetSequenceKey> resolve(ShortenerProperties.Security security) {
        if (!security.getJwtKeys().isEmpty()) {
            List<OctetSequenceKey> resolved = new ArrayList<>();
            List<String> seen = new ArrayList<>();
            for (ShortenerProperties.JwtKey configured : security.getJwtKeys()) {
                if (seen.contains(configured.getId())) {
                    throw new IllegalStateException(
                            "Duplicate JWT key id '" + configured.getId()
                                    + "'. Ids select the verification key, so a duplicate makes "
                                    + "verification depend on ordering rather than on the token.");
                }
                seen.add(configured.getId());
                resolved.add(build(
                        configured.getId(),
                        material(configured.getSecret(), configured.getSecretFile(),
                                "shortener.security.jwt-keys[" + configured.getId() + "]")));
            }
            return List.copyOf(resolved);
        }

        String single = material(security.getJwtSecret(), security.getJwtSecretFile(),
                "shortener.security.jwt-secret");
        if (single != null) {
            return List.of(build("primary", single));
        }

        byte[] generated = new byte[MINIMUM_SECRET_BYTES];
        new SecureRandom().nextBytes(generated);
        log.warn("""
                No JWT signing key configured. Generated a random key for this process only.
                Tokens will not survive a restart and will not validate across instances.
                Set shortener.security.jwt-secret-file (preferred) or SHORTENER_JWT_SECRET \
                before running more than one instance.""");
        return List.of(new OctetSequenceKey.Builder(generated)
                .keyID(GENERATED_KEY_ID)
                .algorithm(JWSAlgorithm.HS256)
                .build());
    }

    /** File takes precedence: a mounted secret is not visible through process inspection. */
    private String material(String inline, String file, String source) {
        if (file != null && !file.isBlank()) {
            try {
                String fromFile = Files.readString(Path.of(file)).trim();
                if (fromFile.isEmpty()) {
                    throw new IllegalStateException(source + " points at an empty file: " + file);
                }
                return fromFile;
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Cannot read signing key file for " + source + ": " + file, e);
            }
        }
        return inline == null || inline.isBlank() ? null : inline;
    }

    private OctetSequenceKey build(String keyId, String secret) {
        byte[] material = secret.getBytes(StandardCharsets.UTF_8);
        if (material.length < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT signing key '" + keyId + "' is " + material.length + " bytes; HS256 requires at least "
                            + MINIMUM_SECRET_BYTES + ". Refusing to start rather than issue weakly signed tokens.");
        }
        return new OctetSequenceKey.Builder(material)
                .keyID(keyId)
                .algorithm(JWSAlgorithm.HS256)
                .build();
    }
}
