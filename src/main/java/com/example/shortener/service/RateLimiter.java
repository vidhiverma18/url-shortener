package com.example.shortener.service;

import com.example.shortener.config.ShortenerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Distributed token bucket for the link-creation endpoint.
 *
 * <p>The whole bucket update runs inside a Lua script so read-modify-write is atomic
 * on the Redis side. Doing it with separate GET/SET calls would let concurrent
 * requests from the same client each observe the same token count and all pass, which
 * defeats the limit precisely when it is needed.
 *
 * <p><b>It fails open.</b> If Redis is unreachable, requests are allowed. That is a
 * conscious availability-over-enforcement choice for a prototype and it is the wrong
 * default for an endpoint that costs money or writes unbounded data; the reasoning and
 * the conditions for revisiting it are in docs/decisions/ADR-005-rate-limiting.md.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private static final String TOKEN_BUCKET_SCRIPT = """
            local key           = KEYS[1]
            local capacity      = tonumber(ARGV[1])
            local refillPerSec  = tonumber(ARGV[2])
            local now           = tonumber(ARGV[3])
            local ttl           = tonumber(ARGV[4])

            local state  = redis.call('HMGET', key, 'tokens', 'updated')
            local tokens = tonumber(state[1])
            local updated = tonumber(state[2])
            if tokens == nil then
              tokens = capacity
              updated = now
            end

            local elapsed = now - updated
            if elapsed < 0 then elapsed = 0 end
            tokens = math.min(capacity, tokens + elapsed * refillPerSec)

            local allowed = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            end

            redis.call('HSET', key, 'tokens', tokens, 'updated', now)
            redis.call('EXPIRE', key, ttl)
            return { allowed, math.floor(tokens) }
            """;

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ShortenerProperties properties;
    private final DefaultRedisScript<List> script;

    @SuppressWarnings("unchecked")
    public RateLimiter(ObjectProvider<StringRedisTemplate> redisProvider, ShortenerProperties properties) {
        this.redisProvider = redisProvider;
        this.properties = properties;
        this.script = new DefaultRedisScript<>(TOKEN_BUCKET_SCRIPT, List.class);
    }

    /** Limits link creation, keyed by the authenticated principal. */
    public Decision tryAcquire(String clientKey) {
        return tryAcquire("create", clientKey,
                properties.getRateLimitBurst(),
                properties.getRateLimitPerMinute() / 60.0);
    }

    /**
     * Limits token issuance, keyed by client address. Its own bucket, because a login
     * attempt is unauthenticated and must not draw down or be shielded by a principal's
     * creation allowance.
     */
    public Decision tryAcquireLogin(String clientAddress) {
        int perMinute = properties.getSecurity().getLoginAttemptsPerMinute();
        return tryAcquire("login", clientAddress, perMinute, perMinute / 60.0);
    }

    private Decision tryAcquire(String bucket, String clientKey, int capacity, double refillPerSec) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return Decision.allowedWithoutLimiting();
        }
        long ttlSeconds = Math.max(60, (long) Math.ceil(capacity / Math.max(refillPerSec, 0.0001)));
        try {
            List<?> result = redis.execute(
                    script,
                    List.of("ratelimit:" + bucket + ":" + clientKey),
                    String.valueOf(capacity),
                    String.valueOf(refillPerSec),
                    String.valueOf(Instant.now().getEpochSecond()),
                    String.valueOf(ttlSeconds));
            if (result == null || result.size() < 2) {
                return Decision.allowedWithoutLimiting();
            }
            boolean allowed = ((Number) result.get(0)).intValue() == 1;
            long remaining = ((Number) result.get(1)).longValue();
            return new Decision(allowed, remaining, capacity, retryAfterSeconds(refillPerSec));
        } catch (RuntimeException e) {
            log.warn("Rate limiter unavailable, allowing request: {}", e.toString());
            return Decision.allowedWithoutLimiting();
        }
    }

    private long retryAfterSeconds(double refillPerSec) {
        return Math.max(1, (long) Math.ceil(1.0 / Math.max(refillPerSec, 0.0001)));
    }

    public record Decision(boolean allowed, long remainingTokens, int capacity, long retryAfterSeconds) {
        static Decision allowedWithoutLimiting() {
            return new Decision(true, -1, -1, 0);
        }
    }
}
