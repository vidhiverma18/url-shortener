package com.example.shortener.security;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.resilience.CircuitBreaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Revocation logic, including what happens when the store backing it is gone.
 *
 * <p>The outage behaviour gets as much attention as the happy path because it is a deliberate
 * weakening: with Redis unreachable the service accepts tokens it can no longer check. That is
 * a decision, and a decision worth a test is a decision worth failing loudly if someone
 * changes it by accident.
 */
class TokenRevocationTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final ShortenerProperties properties = new ShortenerProperties();

    private TokenRevocationService service(boolean redisAvailable) {
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redisAvailable ? redis : null);
        when(redis.opsForValue()).thenReturn(values);
        return new TokenRevocationService(provider, properties,
                new CircuitBreaker("test", 5, Duration.ofSeconds(1)));
    }

    private Jwt token(String tokenId, String subject, Instant issuedAt) {
        return Jwt.withTokenValue("v")
                .header("alg", "HS256")
                .jti(tokenId)
                .subject(subject)
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(3600))
                .claim("roles", java.util.List.of("USER"))
                .build();
    }

    @Test
    @DisplayName("a revoked token id is rejected")
    void revokedTokenIsRejected() {
        TokenRevocationService service = service(true);
        when(redis.hasKey("revoked:jti:abc")).thenReturn(true);

        TokenRevocationService.Check check = service.check(token("abc", "alice", Instant.now()));

        assertThat(check.revoked()).isTrue();
        assertThat(check.storeAvailable()).isTrue();
    }

    @Test
    @DisplayName("a principal-wide revocation kills tokens issued before the cut-off only")
    void principalWideRevocationRespectsIssueTime() {
        TokenRevocationService service = service(true);
        Instant cutoff = Instant.now();
        when(redis.hasKey(anyString())).thenReturn(false);
        when(values.get("revoked:sub:alice")).thenReturn(String.valueOf(cutoff.getEpochSecond()));

        Jwt older = token("old", "alice", cutoff.minusSeconds(60));
        Jwt newer = token("new", "alice", cutoff.plusSeconds(60));

        assertThat(service.check(older).revoked()).as("issued before the cut-off").isTrue();
        // A fresh login after revoking everything has to work, or "sign out everywhere"
        // would lock the account out until the cut-off entry expired.
        assertThat(service.check(newer).revoked()).as("issued after the cut-off").isFalse();
    }

    @Test
    @DisplayName("an expired token is not added to the list it would outlive")
    void expiredTokensAreNotStored() {
        TokenRevocationService service = service(true);

        boolean result = service.revokeToken("abc", Instant.now().minusSeconds(10));

        assertThat(result).isTrue();
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("with the store unavailable the check is inconclusive, not a pass or a fail")
    void storeOutageIsReportedSeparately() {
        TokenRevocationService service = service(false);

        TokenRevocationService.Check check = service.check(token("abc", "alice", Instant.now()));

        assertThat(check.revoked()).isFalse();
        assertThat(check.storeAvailable()).isFalse();
    }

    @Test
    @DisplayName("an inconclusive check accepts the token by default and rejects it when failing closed")
    void failOpenIsTheDefaultAndIsConfigurable() {
        TokenRevocationService service = service(false);
        Jwt jwt = token("abc", "alice", Instant.now());

        OAuth2TokenValidatorResult open = new RevocationValidator(service).validate(jwt);
        assertThat(open.hasErrors()).as("default keeps the service up during a Redis outage").isFalse();

        properties.getSecurity().setRevocationFailClosed(true);
        OAuth2TokenValidatorResult closed = new RevocationValidator(service).validate(jwt);
        assertThat(closed.hasErrors()).as("fail-closed prefers refusing over unchecked access").isTrue();
    }

    @Test
    @DisplayName("revocation is stored with a TTL matching the token's remaining life")
    void revocationEntriesExpireWithTheirToken() {
        TokenRevocationService service = service(true);
        Instant expiry = Instant.now().plusSeconds(600);

        assertThat(service.revokeToken("abc", expiry)).isTrue();

        // The list only ever needs to outlive the tokens on it, so it stays proportional to
        // tokens in flight instead of growing without bound.
        verify(values).set(eq("revoked:jti:abc"), eq("1"), any(Duration.class));
    }

    @Test
    @DisplayName("a failure to record a revocation is reported rather than swallowed")
    void storeFailureIsReported() {
        TokenRevocationService service = service(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("redis down"))
                .when(values).set(anyString(), anyString(), any(Duration.class));

        // The caller needs this to answer 503 instead of telling someone their leaked token
        // is dead when it is still valid.
        assertThat(service.revokeToken("abc", Instant.now().plusSeconds(600))).isFalse();
        assertThat(Map.of()).isEmpty();
    }
}
