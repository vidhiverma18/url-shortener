package com.example.shortener.security.audit;

/**
 * The vocabulary of the audit trail.
 *
 * <p>Constants rather than free-form strings so that querying the trail does not depend on
 * remembering how a call site spelled something. An audit log you cannot reliably filter is
 * an audit log nobody reads.
 */
public final class AuditAction {

    /** An administrator read or acted on a link they do not own. */
    public static final String ADMIN_LINK_ACCESS = "ADMIN_LINK_ACCESS";

    public static final String LINK_RETIRED = "LINK_RETIRED";

    /** Creation refused because the destination failed reputation screening. */
    public static final String LINK_SCREENING_BLOCKED = "LINK_SCREENING_BLOCKED";

    /** A live link disabled by the rescan sweep after its destination turned hostile. */
    public static final String LINK_QUARANTINED = "LINK_QUARANTINED";

    /** Click rate on one link crossed the review threshold. Recorded, not acted on. */
    public static final String ABNORMAL_CLICK_VELOCITY = "ABNORMAL_CLICK_VELOCITY";

    public static final String ACCOUNT_SUSPENDED = "ACCOUNT_SUSPENDED";

    public static final String AUTH_FAILED = "AUTH_FAILED";

    public static final String TOKEN_ISSUED = "TOKEN_ISSUED";

    public static final String TOKEN_REVOKED = "TOKEN_REVOKED";

    /** Every token for a principal invalidated at once. */
    public static final String ALL_TOKENS_REVOKED = "ALL_TOKENS_REVOKED";

    public static final String DOMAIN_BLOCKED = "DOMAIN_BLOCKED";

    public static final String OUTCOME_APPLIED = "APPLIED";
    public static final String OUTCOME_DENIED = "DENIED";
    public static final String OUTCOME_OBSERVED = "OBSERVED";

    public static final String TARGET_LINK = "link";
    public static final String TARGET_USER = "user";
    public static final String TARGET_TOKEN = "token";
    public static final String TARGET_DOMAIN = "domain";

    private AuditAction() {
    }
}
