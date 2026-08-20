package com.example.shortener.service;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.domain.AppUser;
import com.example.shortener.repository.AppUserRepository;
import com.example.shortener.resilience.CircuitBreaker;
import com.example.shortener.security.TokenRevocationService;
import com.example.shortener.security.audit.AuditAction;
import com.example.shortener.security.audit.AuditLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * Watches for patterns that rate limiting cannot see.
 *
 * <p>Rate limiting answers "how fast". This answers "doing what". An account making three
 * requests a minute is well inside every limit, and if all three are attempts to shorten
 * known-malicious destinations it is not a slow client, it is someone probing for a gap in
 * the blocklist.
 *
 * <p>The two signals get deliberately different responses:
 *
 * <ul>
 *   <li><b>Repeated blocked creations suspend the account.</b> There is no legitimate reason
 *       to keep submitting flagged destinations, so the false-positive cost is low and the
 *       response can be automatic.
 *   <li><b>Unusual click velocity is only recorded.</b> A traffic spike is what a successful
 *       campaign and a malicious one look like from here, and disabling a legitimate viral
 *       link is far more expensive than reviewing it late. Automation that cannot tell those
 *       apart should escalate, not act.
 * </ul>
 */
@Component
public class AbuseMonitor {

    private static final Logger log = LoggerFactory.getLogger(AbuseMonitor.class);

    private static final String BLOCKED_PREFIX = "abuse:blocked:";
    private static final String VELOCITY_PREFIX = "abuse:velocity:";

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final AppUserRepository users;
    private final AuditLog audit;
    private final TokenRevocationService revocations;
    private final CircuitBreaker breaker;
    private final ShortenerProperties.Abuse config;

    public AbuseMonitor(ObjectProvider<StringRedisTemplate> redisProvider,
                        AppUserRepository users,
                        AuditLog audit,
                        TokenRevocationService revocations,
                        CircuitBreaker redisCircuitBreaker,
                        ShortenerProperties properties) {
        this.redisProvider = redisProvider;
        this.users = users;
        this.audit = audit;
        this.revocations = revocations;
        this.breaker = redisCircuitBreaker;
        this.config = properties.getAbuse();
    }

    /**
     * Counts a refused creation and suspends the account once the threshold is crossed.
     *
     * <p>Counting in Redis rather than the database keeps a hostile client from turning its
     * own abuse into write load on the primary store.
     */
    public void recordBlockedCreation(String username, String url) {
        audit.recordQuietly(AuditAction.LINK_SCREENING_BLOCKED, AuditAction.OUTCOME_DENIED,
                AuditAction.TARGET_USER, username, "destination refused: " + url);

        long strikes = increment(BLOCKED_PREFIX + username, config.getBlockedCreationWindow());
        if (strikes < config.getBlockedCreationThreshold()) {
            return;
        }
        if (!config.isAutoSuspend()) {
            log.warn("Account '{}' reached {} blocked creations; auto-suspend is off", username, strikes);
            audit.recordQuietly(AuditAction.ACCOUNT_SUSPENDED, AuditAction.OUTCOME_OBSERVED,
                    AuditAction.TARGET_USER, username, "threshold reached but auto-suspend disabled");
            return;
        }
        suspend(username, strikes);
    }

    private void suspend(String username, long strikes) {
        users.findByUsername(username).ifPresent(user -> {
            if (!user.isEnabled()) {
                return;
            }
            user.disable();
            users.save(user);

            // Disabling the account stops new logins; existing tokens would otherwise keep
            // working until they expired, which for a suspension is precisely the window that
            // matters. Revoking closes it.
            revocations.revokeAllFor(username);

            log.warn("Suspended account '{}' after {} blocked creation attempts", username, strikes);
            audit.recordQuietly(AuditAction.ACCOUNT_SUSPENDED, AuditAction.OUTCOME_APPLIED,
                    AuditAction.TARGET_USER, username,
                    strikes + " blocked creation attempts within " + config.getBlockedCreationWindow());
        });
    }

    /**
     * Examines a flushed batch of click events for links receiving abnormal traffic.
     *
     * <p>Called from the analytics flush rather than the redirect itself, so watching for
     * abuse costs the hot path nothing. The counter is per link per minute, and the alert
     * fires once per minute per link because a threshold crossed by a viral link would
     * otherwise generate one audit record per click.
     */
    public void observe(Map<Long, Integer> clicksByLinkId) {
        if (clicksByLinkId.isEmpty()) {
            return;
        }
        long minute = System.currentTimeMillis() / 60_000;
        for (Map.Entry<Long, Integer> entry : clicksByLinkId.entrySet()) {
            String key = VELOCITY_PREFIX + entry.getKey() + ":" + minute;
            long total = incrementBy(key, entry.getValue(), Duration.ofMinutes(2));
            long threshold = config.getClickVelocityPerMinute();

            // Only the batch that carries the count across the line reports it.
            if (total >= threshold && total - entry.getValue() < threshold) {
                log.warn("Link {} received {} clicks this minute, above the {} review threshold",
                        entry.getKey(), total, threshold);
                audit.recordAs("system", AuditAction.ABNORMAL_CLICK_VELOCITY, AuditAction.OUTCOME_OBSERVED,
                        AuditAction.TARGET_LINK, String.valueOf(entry.getKey()),
                        total + " clicks in one minute, threshold " + threshold);
            }
        }
    }

    private long increment(String key, Duration window) {
        return incrementBy(key, 1, window);
    }

    private long incrementBy(String key, int amount, Duration window) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null || !breaker.allowRequest()) {
            return 0;
        }
        try {
            Long value = redis.opsForValue().increment(key, amount);
            redis.expire(key, window);
            breaker.recordSuccess();
            return value == null ? 0 : value;
        } catch (RuntimeException e) {
            breaker.recordFailure();
            // Detection degrades with Redis, it does not block anything. The screening
            // decision that produced this call already stood on its own.
            log.warn("Abuse counter unavailable for {}: {}", key, e.toString());
            return 0;
        }
    }
}
