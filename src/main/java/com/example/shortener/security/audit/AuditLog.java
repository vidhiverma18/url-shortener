package com.example.shortener.security.audit;

import com.example.shortener.domain.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;

/**
 * Writes the audit trail.
 *
 * <p>Every write runs in its own transaction. A denial has to be recorded even though the
 * request that triggered it is about to fail and roll back — an audit trail that only retains
 * successful actions is precisely backwards, since the refused attempts are the interesting
 * ones.
 *
 * <p>Because of that independence, callers must record an {@code APPLIED} outcome only after
 * the action has actually succeeded, or the trail will claim something happened that was
 * subsequently rolled back.
 */
@Service
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);
    private static final int MAX_DETAIL = 512;

    private final AuditWriter writer;

    AuditLog(AuditWriter writer) {
        this.writer = writer;
    }

    /**
     * Records an event, letting a failure propagate.
     *
     * <p>For privileged mutations this is what you want: if the trail cannot be written the
     * database is already broken, so the action was going to fail anyway, and completing a
     * privileged action without recording it is the one outcome worth refusing.
     */
    public void record(String action, String outcome, String targetType, String targetId, String detail) {
        recordAs(currentActor(), action, outcome, targetType, targetId, detail);
    }

    /**
     * Records an event, swallowing failures.
     *
     * <p>For observational events — a failed login, a traffic anomaly — where refusing the
     * request adds no safety and would convert a storage hiccup into an outage.
     */
    public void recordQuietly(String action, String outcome, String targetType, String targetId, String detail) {
        try {
            record(action, outcome, targetType, targetId, detail);
        } catch (RuntimeException e) {
            log.warn("Could not write audit event {} for {}: {}", action, targetId, e.toString());
        }
    }

    /** Records an event on behalf of a named actor, for paths with no security context. */
    public void recordAs(String actor, String action, String outcome,
                         String targetType, String targetId, String detail) {
        writer.write(new AuditEvent(
                Instant.now(), actor, action, targetType, targetId, outcome, currentIp(), truncate(detail)));
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return "anonymous";
        }
        return authentication.getName();
    }

    /**
     * Best-effort client address. Deliberately reads the direct peer rather than
     * {@code X-Forwarded-For}: a spoofable header in an audit trail is worse than no value,
     * because it invites conclusions the data cannot support.
     */
    private String currentIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest().getRemoteAddr();
        }
        return null;
    }

    private String truncate(String detail) {
        if (detail == null || detail.length() <= MAX_DETAIL) {
            return detail;
        }
        return detail.substring(0, MAX_DETAIL - 3) + "...";
    }
}
