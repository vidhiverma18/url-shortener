package com.example.shortener.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "click_events")
public class ClickEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "short_link_id", nullable = false)
    private Long shortLinkId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "referrer_host", length = 255)
    private String referrerHost;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "visitor_hash", length = 64)
    private String visitorHash;

    protected ClickEvent() {
    }

    public ClickEvent(Long shortLinkId, Instant occurredAt, String referrerHost,
                      String userAgent, String visitorHash) {
        this.shortLinkId = shortLinkId;
        this.occurredAt = occurredAt;
        this.referrerHost = referrerHost;
        this.userAgent = userAgent;
        this.visitorHash = visitorHash;
    }

    public Long getId() {
        return id;
    }

    public Long getShortLinkId() {
        return shortLinkId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getReferrerHost() {
        return referrerHost;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getVisitorHash() {
        return visitorHash;
    }
}
