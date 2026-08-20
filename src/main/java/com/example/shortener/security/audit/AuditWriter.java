package com.example.shortener.security.audit;

import com.example.shortener.domain.AuditEvent;
import com.example.shortener.repository.AuditEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists one audit event in a transaction of its own.
 *
 * <p>A separate bean, not a method on {@link AuditLog}, because {@code REQUIRES_NEW} is
 * applied by a proxy and a proxy is bypassed by self-invocation. With the write living on
 * {@code AuditLog}, its own convenience methods called it through {@code this}, the new
 * transaction was silently never started, and the insert joined the caller's transaction
 * instead — which for a read-only caller such as an ownership check failed at flush, turning
 * an administrator's successful read into a 500 with no audit record to show for it.
 *
 * <p>Independence is also what the trail needs: a denial has to survive the rollback of the
 * request that caused it.
 */
@Component
class AuditWriter {

    private final AuditEventRepository events;

    AuditWriter(AuditEventRepository events) {
        this.events = events;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void write(AuditEvent event) {
        events.save(event);
    }
}
