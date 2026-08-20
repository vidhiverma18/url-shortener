package com.example.shortener.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A host refused at creation time, added at runtime rather than by redeploy.
 *
 * <p>The configured blocklist is the static baseline; this table is what an operator can
 * change during an incident. Blocking a domain is the kind of decision that has to be
 * possible in minutes, and a deployment pipeline is not a minutes-scale tool.
 */
@Entity
@Table(name = "blocked_domains")
public class BlockedDomain {

    @Id
    @Column(length = 255)
    private String domain;

    @Column(length = 255)
    private String reason;

    @Column(name = "added_by", length = 128)
    private String addedBy;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt = Instant.now();

    protected BlockedDomain() {
    }

    public BlockedDomain(String domain, String reason, String addedBy) {
        this.domain = domain;
        this.reason = reason;
        this.addedBy = addedBy;
        this.addedAt = Instant.now();
    }

    public String getDomain() {
        return domain;
    }

    public String getReason() {
        return reason;
    }

    public String getAddedBy() {
        return addedBy;
    }

    public Instant getAddedAt() {
        return addedAt;
    }
}
