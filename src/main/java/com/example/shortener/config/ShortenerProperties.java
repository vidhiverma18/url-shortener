package com.example.shortener.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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

    private final Screening screening = new Screening();

    private final Abuse abuse = new Abuse();

    public Screening getScreening() {
        return screening;
    }

    public Abuse getAbuse() {
        return abuse;
    }

    public static class Security {

        /**
         * HMAC signing key for issued tokens, for deployments that do not rotate.
         *
         * <p>Deliberately empty rather than carrying a shipped default. A default signing
         * key committed to a repository is not a placeholder, it is a published private key:
         * anyone with the source can mint an admin token against any deployment that never
         * overrode it. When nothing is configured the application generates a random key at
         * startup and says so loudly, which fails visibly (tokens do not survive a restart
         * or span instances) instead of failing silently and exploitably.
         */
        private String jwtSecret = "";

        /**
         * Path to a file holding the signing key, for Docker and Kubernetes secret mounts.
         * Preferred over the environment variable: env vars leak through {@code /proc},
         * crash dumps, child processes and container inspection APIs.
         */
        private String jwtSecretFile;

        /**
         * Signing keys for rotation, newest first. The first entry signs; every entry
         * verifies. Rotation means adding a key at the front and removing the last one only
         * after the longest possible token lifetime has elapsed, so tokens signed with the
         * outgoing key keep working until they expire naturally.
         */
        private List<JwtKey> jwtKeys = new ArrayList<>();

        /**
         * Whether an unreachable revocation store should reject tokens rather than accept
         * them.
         *
         * <p>Default false, which means a Redis outage suspends revocation rather than
         * suspending the service. That is a real weakening and it is why token lifetime is
         * kept short: the expiry, not the revocation list, is the guarantee. Set true where
         * revoking a token promptly matters more than staying up.
         */
        private boolean revocationFailClosed = false;

        /**
         * Token lifetime. Short on purpose: revocation depends on Redis and fails open by
         * default, so the expiry is the only withdrawal guarantee that always holds.
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

        public String getJwtSecretFile() {
            return jwtSecretFile;
        }

        public void setJwtSecretFile(String jwtSecretFile) {
            this.jwtSecretFile = jwtSecretFile;
        }

        public List<JwtKey> getJwtKeys() {
            return jwtKeys;
        }

        public void setJwtKeys(List<JwtKey> jwtKeys) {
            this.jwtKeys = jwtKeys;
        }

        public boolean isRevocationFailClosed() {
            return revocationFailClosed;
        }

        public void setRevocationFailClosed(boolean revocationFailClosed) {
            this.revocationFailClosed = revocationFailClosed;
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

    /** One signing key in the rotation set. */
    public static class JwtKey {

        /**
         * Stable identifier written into the token's {@code kid} header so a verifier knows
         * which key to try. Without it, rotation means trying every key against every token,
         * which works but turns key count into per-request cost.
         */
        @NotBlank
        private String id;

        private String secret = "";

        /** File holding the key, for secret mounts. Takes precedence over {@code secret}. */
        private String secretFile;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public String getSecretFile() {
            return secretFile;
        }

        public void setSecretFile(String secretFile) {
            this.secretFile = secretFile;
        }
    }

    /** Destination reputation checking, at creation and on a schedule afterwards. */
    public static class Screening {

        private boolean enabled = true;

        /** Exact hosts refused outright, plus every subdomain of them. */
        private List<String> blockedDomains = new ArrayList<>();

        /**
         * Whether an unreachable reputation provider should allow the link through.
         *
         * <p>Default true. Failing closed would let an outage at a third party stop link
         * creation entirely, and the link is re-screened on a schedule regardless, so an
         * unscreened link is caught within one rescan interval rather than never.
         */
        private boolean failOpen = true;

        /** Google Safe Browsing v4 key. The provider stays disabled while this is empty. */
        private String safeBrowsingApiKey = "";

        private String safeBrowsingEndpoint = "https://safebrowsing.googleapis.com/v4/threatMatches:find";

        /**
         * Budget for a reputation lookup. Tight because this sits on the creation path, and
         * a slow provider must degrade to "unknown" rather than hold the request open.
         */
        private Duration providerTimeout = Duration.ofSeconds(2);

        /** Age at which a live link is re-screened. */
        private Duration rescanAfter = Duration.ofHours(24);

        /** How often the rescan sweep runs. */
        private Duration rescanInterval = Duration.ofMinutes(15);

        /** Links examined per sweep, so a large backlog cannot monopolise the database. */
        @Positive
        private int rescanBatchSize = 100;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getBlockedDomains() {
            return blockedDomains;
        }

        public void setBlockedDomains(List<String> blockedDomains) {
            this.blockedDomains = blockedDomains;
        }

        public boolean isFailOpen() {
            return failOpen;
        }

        public void setFailOpen(boolean failOpen) {
            this.failOpen = failOpen;
        }

        public String getSafeBrowsingApiKey() {
            return safeBrowsingApiKey;
        }

        public void setSafeBrowsingApiKey(String safeBrowsingApiKey) {
            this.safeBrowsingApiKey = safeBrowsingApiKey;
        }

        public String getSafeBrowsingEndpoint() {
            return safeBrowsingEndpoint;
        }

        public void setSafeBrowsingEndpoint(String safeBrowsingEndpoint) {
            this.safeBrowsingEndpoint = safeBrowsingEndpoint;
        }

        public Duration getProviderTimeout() {
            return providerTimeout;
        }

        public void setProviderTimeout(Duration providerTimeout) {
            this.providerTimeout = providerTimeout;
        }

        public Duration getRescanAfter() {
            return rescanAfter;
        }

        public void setRescanAfter(Duration rescanAfter) {
            this.rescanAfter = rescanAfter;
        }

        public Duration getRescanInterval() {
            return rescanInterval;
        }

        public void setRescanInterval(Duration rescanInterval) {
            this.rescanInterval = rescanInterval;
        }

        public int getRescanBatchSize() {
            return rescanBatchSize;
        }

        public void setRescanBatchSize(int rescanBatchSize) {
            this.rescanBatchSize = rescanBatchSize;
        }
    }

    /** Detection and response for patterns that rate limiting alone does not catch. */
    public static class Abuse {

        /**
         * Refused creation attempts within the window before an account is suspended.
         * Rate limiting caps how fast someone can try; this responds to <em>what</em> they
         * are trying, which is the difference between a busy client and a hostile one.
         */
        @Positive
        private int blockedCreationThreshold = 5;

        private Duration blockedCreationWindow = Duration.ofHours(1);

        /** Whether crossing that threshold disables the account or only records it. */
        private boolean autoSuspend = true;

        /**
         * Clicks per minute on a single link before it is flagged for review.
         *
         * <p>Flagged, never auto-disabled: a sudden spike is what a successful campaign and
         * a malicious one look like from here, and taking down a legitimate viral link is
         * the more expensive mistake.
         */
        @Positive
        private int clickVelocityPerMinute = 600;

        public int getBlockedCreationThreshold() {
            return blockedCreationThreshold;
        }

        public void setBlockedCreationThreshold(int blockedCreationThreshold) {
            this.blockedCreationThreshold = blockedCreationThreshold;
        }

        public Duration getBlockedCreationWindow() {
            return blockedCreationWindow;
        }

        public void setBlockedCreationWindow(Duration blockedCreationWindow) {
            this.blockedCreationWindow = blockedCreationWindow;
        }

        public boolean isAutoSuspend() {
            return autoSuspend;
        }

        public void setAutoSuspend(boolean autoSuspend) {
            this.autoSuspend = autoSuspend;
        }

        public int getClickVelocityPerMinute() {
            return clickVelocityPerMinute;
        }

        public void setClickVelocityPerMinute(int clickVelocityPerMinute) {
            this.clickVelocityPerMinute = clickVelocityPerMinute;
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
