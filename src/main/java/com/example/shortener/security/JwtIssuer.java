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

/**
 * Mints signed access tokens.
 *
 * <p>Claims are kept to the minimum the service actually authorizes on: subject and roles.
 * Every additional claim is a copy of state that cannot be invalidated until the token
 * expires, so putting anything mutable in here creates a window where the token disagrees
 * with the database and the token wins.
 */
@Component
public class JwtIssuer {

    private static final String ROLE_PREFIX = "ROLE_";

    private final JwtEncoder encoder;
    private final ShortenerProperties properties;

    public JwtIssuer(JwtEncoder encoder, ShortenerProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
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

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("url-shortener")
                .issuedAt(now)
                .expiresAt(expiry)
                .subject(authentication.getName())
                .claim("roles", roles)
                .build();

        String value = encoder
                .encode(JwtEncoderParameters.from(JwsHeader.with(SecurityConfig.JWS_ALGORITHM).build(), claims))
                .getTokenValue();

        return new IssuedToken(value, properties.getSecurity().getTokenTtl().toSeconds(), roles);
    }

    public record IssuedToken(String value, long expiresInSeconds, List<String> roles) {
    }
}
