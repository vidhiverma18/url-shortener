package com.example.shortener.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "short_links")
public class ShortLink {

    @Id
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(name = "original_url", nullable = false)
    private String originalUrl;

    @Column(name = "custom_alias", nullable = false)
    private boolean customAlias;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_by", length = 128)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at")
    private Instant expiresAt;

    /**
     * Lookup key for reusing this link when its URL is submitted again. Null means the
     * link is excluded from reuse — see {@code ShortLinkService.create}.
     */
    @Column(name = "url_hash", length = 64)
    private String urlHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "screening_status", nullable = false, length = 16)
    private ScreeningStatus screeningStatus = ScreeningStatus.PENDING;

    @Column(name = "screened_at")
    private Instant screenedAt;

    @Column(name = "quarantined_at")
    private Instant quarantinedAt;

    protected ShortLink() {
    }

    public ShortLink(Long id, String code, String originalUrl, boolean customAlias,
                     String createdBy, Instant expiresAt, String urlHash) {
        this.id = id;
        this.code = code;
        this.originalUrl = originalUrl;
        this.customAlias = customAlias;
        this.createdBy = createdBy;
        this.expiresAt = expiresAt;
        this.urlHash = urlHash;
        this.createdAt = Instant.now();
        this.active = true;
    }

    /**
     * A link is resolvable only while it is active and unexpired. Expiry is evaluated
     * at read time rather than swept by a background job: a sweeper would add a moving
     * part for no user-visible benefit, and lazy evaluation means a clock skew or a
     * missed job can never resurrect a dead link.
     */
    public boolean isResolvable(Instant now) {
        return active && (expiresAt == null || expiresAt.isAfter(now));
    }

    /**
     * Retiring a link also releases its deduplication slot: the unique index is partial on
     * {@code active}, so the same URL can be shortened again afterwards and gets a fresh
     * code rather than resurrecting this one.
     */
    public void deactivate() {
        this.active = false;
    }

    public String getUrlHash() {
        return urlHash;
    }

    /**
     * Disables the link because its destination was found hostile.
     *
     * <p>Separate from {@link #deactivate()} even though both clear {@code active}: the owner
     * did not ask for this, so the redirect explains it rather than behaving as though the
     * link never existed, and the timestamp is what lets an appeal be investigated.
     */
    public void quarantine(Instant when) {
        this.active = false;
        this.quarantinedAt = when;
        this.screeningStatus = ScreeningStatus.BLOCKED;
        this.screenedAt = when;
    }

    public void recordScreening(ScreeningStatus status, Instant when) {
        this.screeningStatus = status;
        this.screenedAt = when;
    }

    public boolean isQuarantined() {
        return quarantinedAt != null;
    }

    public ScreeningStatus getScreeningStatus() {
        return screeningStatus;
    }

    public Instant getScreenedAt() {
        return screenedAt;
    }

    public Instant getQuarantinedAt() {
        return quarantinedAt;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public boolean isCustomAlias() {
        return customAlias;
    }

    public boolean isActive() {
        return active;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
