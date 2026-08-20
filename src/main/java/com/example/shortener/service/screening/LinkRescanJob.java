package com.example.shortener.service.screening;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.domain.ShortLink;
import com.example.shortener.repository.ShortLinkRepository;
import com.example.shortener.security.audit.AuditAction;
import com.example.shortener.security.audit.AuditLog;
import com.example.shortener.service.LinkCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Re-screens live links and takes down the ones whose destination has turned hostile.
 *
 * <p>Screening at creation is necessary and not sufficient. The standard evasion is to shorten
 * a benign page, wait for it to pass, then repoint or compromise the destination — the short
 * code never changes and a create-time check never looks again. This closes that window to one
 * rescan interval. It is also what makes fail-open at creation defensible: a link that slipped
 * through while a provider was unreachable is examined again shortly afterwards rather than
 * never.
 *
 * <p>Work is capped per sweep. A large backlog after an outage must not turn into a long
 * transaction, a burst of outbound calls to a rate-limited provider, or a database scan
 * competing with live traffic.
 */
@Component
public class LinkRescanJob {

    private static final Logger log = LoggerFactory.getLogger(LinkRescanJob.class);

    private final ShortLinkRepository links;
    private final UrlScreeningService screener;
    private final LinkCache cache;
    private final AuditLog audit;
    private final ShortenerProperties.Screening config;

    public LinkRescanJob(ShortLinkRepository links,
                         UrlScreeningService screener,
                         LinkCache cache,
                         AuditLog audit,
                         ShortenerProperties properties) {
        this.links = links;
        this.screener = screener;
        this.cache = cache;
        this.audit = audit;
        this.config = properties.getScreening();
    }

    @Scheduled(fixedDelayString = "${shortener.screening.rescan-interval:PT15M}")
    public void sweep() {
        if (!config.isEnabled()) {
            return;
        }
        try {
            rescanBatch();
        } catch (RuntimeException e) {
            // A scheduled method that throws is silently never rescheduled by some
            // executors, which would disable screening permanently after one bad sweep.
            log.error("Rescan sweep failed, will retry next interval: {}", e.toString());
        }
    }

    /** @return how many links this batch quarantined */
    public int rescanBatch() {
        return rescan(Instant.now().minus(config.getRescanAfter()));
    }

    /**
     * Re-screens every live link regardless of when it was last checked.
     *
     * <p>Exists because the scheduled sweep only looks at links older than the rescan
     * interval, and the moment an operator most wants a sweep is immediately after blocking a
     * domain — at which point the links that matter were screened seconds ago and the periodic
     * job would ignore them for another day.
     */
    public int rescanAll() {
        return rescan(Instant.now());
    }

    private int rescan(Instant cutoff) {
        List<ShortLink> due = links.findRescanCandidates(cutoff, PageRequest.of(0, config.getRescanBatchSize()));
        if (due.isEmpty()) {
            return 0;
        }

        int quarantined = 0;
        for (ShortLink link : due) {
            UrlScreeningService.Decision decision = screener.screen(link.getOriginalUrl());
            if (decision.blocked()) {
                quarantine(link, decision);
                quarantined++;
            } else {
                // Records UNKNOWN as well as CLEAN. Without the timestamp moving, a
                // destination the provider cannot reach would be retried on every sweep
                // forever and starve everything behind it in the queue.
                link.recordScreening(decision.status(), Instant.now());
                links.save(link);
            }
        }

        log.info("Rescanned {} links, quarantined {}", due.size(), quarantined);
        return quarantined;
    }

    private void quarantine(ShortLink link, UrlScreeningService.Decision decision) {
        link.quarantine(Instant.now());
        links.save(link);
        // Cache the takedown rather than merely evicting: these are the links most likely to
        // be under load, and evicting alone would send every subsequent visitor to the
        // database to be told the same thing.
        cache.putQuarantined(link.getCode());

        log.warn("Quarantined link '{}' after rescan: {}", link.getCode(), decision.reason());
        audit.recordAs("system", AuditAction.LINK_QUARANTINED, AuditAction.OUTCOME_APPLIED,
                AuditAction.TARGET_LINK, link.getCode(),
                "flagged by " + decision.source() + ": " + decision.reason());
    }
}
