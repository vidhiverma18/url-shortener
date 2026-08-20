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

    /**
     * Consecutive Redis failures before the breaker opens. Low, because the cost of being
     * wrong is asymmetric: opening needlessly costs one database read per request, while
     * staying closed against a hung server costs a full timeout on every request.
     */
    @Positive
    private int cacheCircuitFailureThreshold = 5;

    /** How long Redis is skipped once the breaker opens, before a single probe is admitted. */
    private Duration cacheCircuitCooldown = Duration.ofSeconds(5);

    public int getCacheCircuitFailureThreshold() {
        return cacheCircuitFailureThreshold;
    }

    public void setCacheCircuitFailureThreshold(int cacheCircuitFailureThreshold) {
        this.cacheCircuitFailureThreshold = cacheCircuitFailureThreshold;
    }

    public Duration getCacheCircuitCooldown() {
        return cacheCircuitCooldown;
    }

    public void setCacheCircuitCooldown(Duration cacheCircuitCooldown) {
        this.cacheCircuitCooldown = cacheCircuitCooldown;
    }

    private final Security security = new Security();

    public Security getSecurity() {
        return security;
    }

    public static class Security {

        /**
         * HMAC signing key for issued tokens. HS256 requires at least 256 bits, and the
         * application refuses to start if this is shorter, because a short key silently
         * weakens every token rather than failing loudly.
         */
        @NotBlank
        private String jwtSecret = "local-development-signing-key-change-me-please-32b";

        /**
         * Token lifetime. Short because there is no revocation list: a stateless token
         * cannot be withdrawn before it expires, so the expiry <em>is</em> the revocation
         * window.
         */
        private Duration tokenTtl = Duration.ofHours(1);

        /** Failed and successful token requests allowed per client address, per minute. */
        @Positive
        private int loginAttemptsPerMinute = 10;

        /**
         * Creates the demo accounts described in the README on startup. Convenience for
         * reviewers; it logs a warning every time and must be off in any real deployment.
         */
        private boolean seedDemoUsers = true;

        public String getJwtSecret() {
            return jwtSecret;
        }

        public void setJwtSecret(String jwtSecret) {
            this.jwtSecret = jwtSecret;
        }

        public Duration getTokenTtl() {
            return tokenTtl;
        }

        public void setTokenTtl(Duration tokenTtl) {
            this.tokenTtl = tokenTtl;
        }

        public int getLoginAttemptsPerMinute() {
            return loginAttemptsPerMinute;
        }

        public void setLoginAttemptsPerMinute(int loginAttemptsPerMinute) {
            this.loginAttemptsPerMinute = loginAttemptsPerMinute;
        }

        public boolean isSeedDemoUsers() {
            return seedDemoUsers;
        }

        public void setSeedDemoUsers(boolean seedDemoUsers) {
            this.seedDemoUsers = seedDemoUsers;
        }
    }

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
