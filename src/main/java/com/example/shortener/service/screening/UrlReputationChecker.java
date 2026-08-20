package com.example.shortener.service.screening;

import com.example.shortener.domain.ScreeningStatus;

/**
 * One source of opinion about whether a destination is safe.
 *
 * <p>The one genuine port in the codebase, and it earns the indirection: the local blocklist
 * and a third-party threat feed have nothing in common operationally — one is instant and
 * always available, the other is slow, rate-limited and sometimes down — but the creation path
 * should not know which kind it is talking to.
 *
 * <p>Implementations must never throw. A checker that cannot reach a verdict returns
 * {@link ScreeningStatus#UNKNOWN}, because the decision about what an inconclusive answer
 * means belongs to policy in {@link UrlScreeningService}, not to the checker.
 */
public interface UrlReputationChecker {

    /** Stable name, used in audit records so a verdict can be traced to its source. */
    String name();

    boolean enabled();

    Reputation check(String url);

    record Reputation(ScreeningStatus status, String reason) {

        public static Reputation clean() {
            return new Reputation(ScreeningStatus.CLEAN, null);
        }

        public static Reputation blocked(String reason) {
            return new Reputation(ScreeningStatus.BLOCKED, reason);
        }

        public static Reputation unknown(String reason) {
            return new Reputation(ScreeningStatus.UNKNOWN, reason);
        }
    }
}
