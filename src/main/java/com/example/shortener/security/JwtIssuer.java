package com.example.shortener.security;

import com.example.shortener.config.ShortenerProperties;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Mints signed access tokens.
 *
 * <p>Claims are kept to the minimum the service actually authorizes on: subject, roles, and a
 * token id. Every additional claim is a copy of state that cannot be invalidated until the
 * token expires, so putting anything mutable in here creates a window where the token
 * disagrees with the database and the token wins.
 */
@Component
public class JwtIssuer {

    private static final String ROLE_PREFIX = "ROLE_";

    private final JwtEncoder encoder;
    private final ShortenerProperties properties;
    private final JwtKeyRing keyRing;

    public JwtIssuer(JwtEncoder encoder, ShortenerProperties properties, JwtKeyRing keyRing) {
        this.encoder = encoder;
        this.properties = properties;
        this.keyRing = keyRing;
    }

    public IssuedToken issue(Authentication authentication) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.getSecurity().getTokenTtl());

        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(authority -> authority.startsWith(ROLE_PREFIX)
                        ? authority.substring(ROLE_PREFIX.length())
                        : authority)
                .toList();

        // A unique id per token is what makes revoking one of them possible. Without it the
        // only unit of withdrawal is the principal, so a single leaked token would mean
        // signing out every session that principal has.
        String tokenId = UUID.randomUUID().toString();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(SecurityConfig.ISSUER)
                .issuedAt(now)
                .expiresAt(expiry)
                .subject(authentication.getName())
                .id(tokenId)
                .claim("roles", roles)
                .build();

        // The key id travels in the header so a verifier holding several keys selects the
        // right one directly instead of trying each in turn.
        JwsHeader header = JwsHeader.with(SecurityConfig.JWS_ALGORITHM)
                .keyId(keyRing.activeKeyId())
                .build();

        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return new IssuedToken(
                value, tokenId, expiry, properties.getSecurity().getTokenTtl().toSeconds(), roles);
    }

    public record IssuedToken(String value, String tokenId, Instant expiresAt,
                              long expiresInSeconds, List<String> roles) {
    }
}
