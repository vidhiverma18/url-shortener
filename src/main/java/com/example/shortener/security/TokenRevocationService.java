package com.example.shortener.security;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.resilience.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Withdraws tokens before they expire.
 *
 * <p>Two mechanisms, because they answer different questions. Revoking a single {@code jti}
 * handles "this token leaked". Revoking everything issued to a principal before a cut-off
 * handles "this account is compromised", which cannot be done by listing tokens because the
 * service never recorded which ones it issued.
 *
 * <p>Entries are stored with a TTL matching the token's remaining life. A revocation list
 * only ever needs to outlive the tokens on it, so it stays proportional to tokens in flight
 * rather than growing forever.
 *
 * <p><b>Availability trade-off.</b> With Redis unreachable this fails open by default: tokens
 * are accepted and revocation is suspended. Failing closed would turn a cache outage into a
 * total authentication outage, which for this service is the worse failure. The mitigation is
 * that token lifetime is short, so expiry — not this list — is the guarantee that always
 * holds. Set {@code shortener.security.revocation-fail-closed} to invert the choice.
 */
@Service
public class TokenRevocationService {

    private static final Logger log = LoggerFactory.getLogger(TokenRevocationService.class);

    private static final String TOKEN_PREFIX = "revoked:jti:";
    private static final String SUBJECT_PREFIX = "revoked:sub:";

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ShortenerProperties properties;
    private final CircuitBreaker breaker;

    public TokenRevocationService(ObjectProvider<StringRedisTemplate> redisProvider,
                                  ShortenerProperties properties,
                                  CircuitBreaker redisCircuitBreaker) {
        this.redisProvider = redisProvider;
        this.properties = properties;
        this.breaker = redisCircuitBreaker;
    }

    /** @return false when the revocation could not be stored, so the caller can say so */
    public boolean revokeToken(String tokenId, Instant expiresAt) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return false;
        }
        Duration remaining = Duration.between(Instant.now(), expiresAt);
        if (remaining.isNegative() || remaining.isZero()) {
            // Already expired; the list would outlive the token it describes.
            return true;
        }
        try {
            redis.opsForValue().set(TOKEN_PREFIX + tokenId, "1", remaining);
            breaker.recordSuccess();
            return true;
        } catch (RuntimeException e) {
            breaker.recordFailure();
            log.warn("Could not record token revocation: {}", e.toString());
            return false;
        }
    }

    /**
     * Invalidates every token issued to a principal up to now.
     *
     * <p>The cut-off is stored rather than the tokens, and held for one full token lifetime,
     * after which every token it could have applied to has expired on its own.
     */
    public boolean revokeAllFor(String subject) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return false;
        }
        try {
            redis.opsForValue().set(
                    SUBJECT_PREFIX + subject,
                    String.valueOf(Instant.now().getEpochSecond()),
                    properties.getSecurity().getTokenTtl());
            breaker.recordSuccess();
            return true;
        } catch (RuntimeException e) {
            breaker.recordFailure();
            log.warn("Could not record principal-wide revocation for '{}': {}", subject, e.toString());
            return false;
        }
    }

    public Check check(Jwt jwt) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null || !breaker.allowRequest()) {
            return Check.storeUnavailable();
        }
        try {
            String tokenId = jwt.getId();
            if (tokenId != null && Boolean.TRUE.equals(redis.hasKey(TOKEN_PREFIX + tokenId))) {
                breaker.recordSuccess();
                return Check.revoked("token was revoked");
            }

            String cutoff = redis.opsForValue().get(SUBJECT_PREFIX + jwt.getSubject());
            breaker.recordSuccess();
            if (cutoff != null && jwt.getIssuedAt() != null
                    && jwt.getIssuedAt().getEpochSecond() <= Long.parseLong(cutoff)) {
                return Check.revoked("all tokens for this principal were revoked");
            }
            return Check.valid();
        } catch (RuntimeException e) {
            breaker.recordFailure();
            log.warn("Revocation store unreachable: {}", e.toString());
            return Check.storeUnavailable();
        }
    }

    public boolean failClosed() {
        return properties.getSecurity().isRevocationFailClosed();
    }

    public record Check(boolean revoked, boolean storeAvailable, String reason) {

        static Check valid() {
            return new Check(false, true, null);
        }

        static Check revoked(String reason) {
            return new Check(true, true, reason);
        }

        static Check storeUnavailable() {
            return new Check(false, false, null);
        }
    }
}
