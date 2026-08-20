package com.example.shortener.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "shortener")
public class ShortenerProperties {

    /** Public origin used to build short URLs returned to clients. */
    @NotBlank
    private String baseUrl = "http://localhost:8080";

    /**
     * Key for the Feistel permutation that scrambles sequential ids into
     * non-enumerable short codes. Must be stable for the lifetime of the
     * deployment: changing it does not invalidate existing links (codes are
     * persisted, not recomputed) but it does change codes minted afterwards.
     */
    @NotBlank
    private String codeSecret = "local-development-only-change-me";

    /** How long a resolved link stays in the read-through cache. */
    private Duration cacheTtl = Duration.ofMinutes(10);

    /** Short TTL for "this code does not exist" entries, to blunt cache penetration. */
    private Duration negativeCacheTtl = Duration.ofSeconds(30);

    /** Token bucket size for the link-creation endpoint, per client key. */
    @Positive
    private int rateLimitBurst = 20;

    /** Sustained refill rate, tokens per minute, for the link-creation endpoint. */
    @Positive
    private int rateLimitPerMinute = 60;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getCodeSecret() {
        return codeSecret;
    }

    public void setCodeSecret(String codeSecret) {
        this.codeSecret = codeSecret;
    }

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }

    public Duration getNegativeCacheTtl() {
        return negativeCacheTtl;
    }

    public void setNegativeCacheTtl(Duration negativeCacheTtl) {
        this.negativeCacheTtl = negativeCacheTtl;
    }

    public int getRateLimitBurst() {
        return rateLimitBurst;
    }

    public void setRateLimitBurst(int rateLimitBurst) {
        this.rateLimitBurst = rateLimitBurst;
    }

    public int getRateLimitPerMinute() {
        return rateLimitPerMinute;
    }

    public void setRateLimitPerMinute(int rateLimitPerMinute) {
        this.rateLimitPerMinute = rateLimitPerMinute;
    }
}
