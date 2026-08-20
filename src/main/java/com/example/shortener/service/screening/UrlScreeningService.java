package com.example.shortener.service.screening;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.domain.ScreeningStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Decides what the checkers' opinions add up to.
 *
 * <p>Policy lives here rather than in the checkers so that the rules are in one readable place:
 *
 * <ul>
 *   <li>Any checker saying blocked blocks. Reputation is not a vote — one credible report of
 *       malware outweighs any number of sources that simply have not heard of the domain.
 *   <li>Otherwise, a clean answer from at least one enabled checker is clean.
 *   <li>Otherwise the verdict is unknown, and {@code fail-open} decides whether that permits
 *       the link. It defaults to permitting, because refusing would let an outage at a third
 *       party stop link creation entirely, and the rescan sweep revisits unknown verdicts —
 *       so an unscreened link is caught within one interval rather than never.
 * </ul>
 */
@Service
public class UrlScreeningService {

    private static final Logger log = LoggerFactory.getLogger(UrlScreeningService.class);

    private final List<UrlReputationChecker> checkers;
    private final ShortenerProperties.Screening config;

    public UrlScreeningService(List<UrlReputationChecker> checkers, ShortenerProperties properties) {
        this.checkers = checkers;
        this.config = properties.getScreening();
    }

    public Decision screen(String url) {
        if (!config.isEnabled()) {
            return new Decision(ScreeningStatus.PENDING, true, null, null);
        }

        boolean anyClean = false;
        for (UrlReputationChecker checker : checkers) {
            if (!checker.enabled()) {
                continue;
            }
            UrlReputationChecker.Reputation reputation = checker.check(url);
            if (reputation.status() == ScreeningStatus.BLOCKED) {
                log.info("Screening blocked a destination via {}: {}", checker.name(), reputation.reason());
                return new Decision(ScreeningStatus.BLOCKED, false, checker.name(), reputation.reason());
            }
            if (reputation.status() == ScreeningStatus.CLEAN) {
                anyClean = true;
            }
        }

        if (anyClean) {
            return new Decision(ScreeningStatus.CLEAN, true, null, null);
        }
        return new Decision(ScreeningStatus.UNKNOWN, config.isFailOpen(), null, "no checker reached a verdict");
    }

    /**
     * @param allowed whether the link may be created or stay live; false only for a blocked
     *                verdict, or for an unknown one where the deployment fails closed
     * @param source  which checker produced a blocking verdict, recorded so an appeal can be
     *                traced back to the feed that caused it
     */
    public record Decision(ScreeningStatus status, boolean allowed, String source, String reason) {

        public boolean blocked() {
            return status == ScreeningStatus.BLOCKED;
        }
    }
}
