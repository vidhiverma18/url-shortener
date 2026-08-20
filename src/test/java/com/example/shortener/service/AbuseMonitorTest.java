package com.example.shortener.service;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.domain.AppUser;
import com.example.shortener.repository.AppUserRepository;
import com.example.shortener.resilience.CircuitBreaker;
import com.example.shortener.security.TokenRevocationService;
import com.example.shortener.security.audit.AuditAction;
import com.example.shortener.security.audit.AuditLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Abuse response, which is where the two signals are deliberately treated differently.
 *
 * <p>Repeated refused creations suspend an account automatically because there is no innocent
 * reason to keep submitting flagged destinations. A traffic spike only raises a flag, because
 * a legitimate viral link and a malicious one are indistinguishable from here and taking down
 * the wrong one is the more expensive mistake. Both halves are asserted so neither can be
 * "tidied" into the other.
 */
class AbuseMonitorTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final TokenRevocationService revocations = mock(TokenRevocationService.class);
    private final ShortenerProperties properties = new ShortenerProperties();

    private AbuseMonitor monitor(boolean redisAvailable) {
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redisAvailable ? redis : null);
        when(redis.opsForValue()).thenReturn(values);
        return new AbuseMonitor(provider, users, audit, revocations,
                new CircuitBreaker("test", 5, Duration.ofSeconds(1)), properties);
    }

    private AppUser enabledUser() {
        return new AppUser("mallory", "hash", "USER");
    }

    @Test
    @DisplayName("an account is suspended once refused creations cross the threshold")
    void repeatedBlockedCreationsSuspendTheAccount() {
        AbuseMonitor monitor = monitor(true);
        AppUser user = enabledUser();
        when(users.findByUsername("mallory")).thenReturn(Optional.of(user));
        when(values.increment(anyString(), eq(1L)))
                .thenReturn((long) properties.getAbuse().getBlockedCreationThreshold());

        monitor.recordBlockedCreation("mallory", "https://bad.example");

        assertThat(user.isEnabled()).isFalse();
        verify(users).save(user);
        // Disabling the account stops new logins but leaves tokens already issued working
        // until they expire, which for a suspension is precisely the window that matters.
        verify(revocations).revokeAllFor("mallory");
    }

    @Test
    @DisplayName("an account below the threshold is left alone")
    void isolatedBlockedCreationsDoNotSuspend() {
        AbuseMonitor monitor = monitor(true);
        AppUser user = enabledUser();
        when(users.findByUsername("mallory")).thenReturn(Optional.of(user));
        when(values.increment(anyString(), eq(1L))).thenReturn(1L);

        monitor.recordBlockedCreation("mallory", "https://bad.example");

        assertThat(user.isEnabled()).isTrue();
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("every refused creation is recorded even when it does not trigger a suspension")
    void everyRefusalIsAudited() {
        AbuseMonitor monitor = monitor(true);
        when(values.increment(anyString(), eq(1L))).thenReturn(1L);

        monitor.recordBlockedCreation("mallory", "https://bad.example");

        verify(audit).recordQuietly(eq(AuditAction.LINK_SCREENING_BLOCKED), eq(AuditAction.OUTCOME_DENIED),
                anyString(), eq("mallory"), anyString());
    }

    @Test
    @DisplayName("auto-suspend can be turned off without turning off detection")
    void autoSuspendIsOptional() {
        properties.getAbuse().setAutoSuspend(false);
        AbuseMonitor monitor = monitor(true);
        AppUser user = enabledUser();
        when(users.findByUsername("mallory")).thenReturn(Optional.of(user));
        when(values.increment(anyString(), eq(1L))).thenReturn(50L);

        monitor.recordBlockedCreation("mallory", "https://bad.example");

        assertThat(user.isEnabled()).isTrue();
        verify(audit).recordQuietly(eq(AuditAction.ACCOUNT_SUSPENDED), eq(AuditAction.OUTCOME_OBSERVED),
                anyString(), eq("mallory"), anyString());
    }

    @Test
    @DisplayName("an unusual click rate is flagged for review, never acted on automatically")
    void clickVelocityIsObservedNotEnforced() {
        AbuseMonitor monitor = monitor(true);
        int threshold = properties.getAbuse().getClickVelocityPerMinute();
        when(values.increment(anyString(), eq((long) threshold))).thenReturn((long) threshold);

        monitor.observe(Map.of(7L, threshold));

        verify(audit).recordAs(eq("system"), eq(AuditAction.ABNORMAL_CLICK_VELOCITY),
                eq(AuditAction.OUTCOME_OBSERVED), anyString(), eq("7"), anyString());
        // The link keeps working: a spike is a reason to look, not a reason to take it down.
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("crossing the click threshold reports once, not once per batch after it")
    void velocityAlertDoesNotRepeatWithinTheMinute() {
        AbuseMonitor monitor = monitor(true);
        int threshold = properties.getAbuse().getClickVelocityPerMinute();
        // A batch of 10 arriving when the counter is already well past the line.
        when(values.increment(anyString(), eq(10L))).thenReturn((long) threshold + 500);

        monitor.observe(Map.of(7L, 10));

        // Otherwise a viral link would generate an audit record for every flush for as long
        // as it stayed popular, burying the trail it was supposed to make visible.
        verify(audit, never()).recordAs(anyString(), eq(AuditAction.ABNORMAL_CLICK_VELOCITY),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("with Redis unavailable detection degrades and nothing is suspended by accident")
    void detectionDegradesWithoutRedis() {
        AbuseMonitor monitor = monitor(false);
        AppUser user = enabledUser();
        when(users.findByUsername("mallory")).thenReturn(Optional.of(user));

        monitor.recordBlockedCreation("mallory", "https://bad.example");
        monitor.observe(Map.of(7L, 10_000));

        // The counter reads zero rather than throwing, so an outage loses detection instead
        // of producing a suspension nobody can explain.
        assertThat(user.isEnabled()).isTrue();
        verify(users, never()).save(any());
    }
}
