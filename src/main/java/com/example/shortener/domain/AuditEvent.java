package com.example.shortener.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One security-relevant action, written once and never changed.
 *
 * <p>There are no setters and the table rejects UPDATE and DELETE at the database level. The
 * entity is immutable so that ordinary JPA dirty-checking cannot produce a write that the
 * trigger then rejects at flush time, turning a coding mistake into a failed request
 * somewhere unrelated.
 */
@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(nullable = false, length = 128)
    private String actor;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(name = "target_type", length = 32)
    private String targetType;

    @Column(name = "target_id", length = 128)
    private String targetId;

    @Column(nullable = false, length = 16)
    private String outcome;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(length = 512)
    private String detail;

    protected AuditEvent() {
    }

    public AuditEvent(Instant occurredAt, String actor, String action, String targetType,
                      String targetId, String outcome, String clientIp, String detail) {
        this.occurredAt = occurredAt;
        this.actor = actor;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.outcome = outcome;
        this.clientIp = clientIp;
        this.detail = detail;
    }

    public Long getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getActor() {
        return actor;
    }

    public String getAction() {
        return action;
    }

    public String getTargetType() {
        return targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getClientIp() {
        return clientIp;
    }

    public String getDetail() {
        return detail;
    }
}
