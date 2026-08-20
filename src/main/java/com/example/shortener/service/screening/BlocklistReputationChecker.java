package com.example.shortener.service.screening;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.domain.BlockedDomain;
import com.example.shortener.repository.BlockedDomainRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Refuses destinations on a locally held blocklist.
 *
 * <p>Two sources, deliberately. Configuration carries the static baseline that ships with a
 * deployment; the database table carries whatever an operator adds during an incident.
 * Blocking a domain is a minutes-scale decision and a deployment pipeline is not a
 * minutes-scale tool, so requiring a redeploy would mean the control exists but cannot be used
 * when it is needed.
 *
 * <p>The table is read into a snapshot rather than queried per request. Creation is not the
 * hot path, but a database round trip on a table of a few hundred rows to answer every create
 * is waste, and the refresh interval bounds how stale a decision can be.
 */
@Component
public class BlocklistReputationChecker implements UrlReputationChecker {

    private static final Logger log = LoggerFactory.getLogger(BlocklistReputationChecker.class);

    private final BlockedDomainRepository blockedDomains;
    private final Set<String> configured;

    private volatile Set<String> snapshot = Set.of();

    public BlocklistReputationChecker(BlockedDomainRepository blockedDomains,
                                      ShortenerProperties properties) {
        this.blockedDomains = blockedDomains;
        this.configured = properties.getScreening().getBlockedDomains().stream()
                .map(domain -> domain.toLowerCase(Locale.ROOT).trim())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public String name() {
        return "local-blocklist";
    }

    @Override
    public boolean enabled() {
        return true;
    }

    /** Refreshes the snapshot, and runs once at startup so the first request is not unguarded. */
    @Scheduled(initialDelay = 0, fixedDelayString = "${shortener.screening.blocklist-refresh-ms:60000}")
    public void refresh() {
        try {
            Set<String> loaded = new HashSet<>(configured);
            for (BlockedDomain blocked : blockedDomains.findAll()) {
                loaded.add(blocked.getDomain().toLowerCase(Locale.ROOT).trim());
            }
            this.snapshot = Set.copyOf(loaded);
        } catch (RuntimeException e) {
            // Keep serving the previous snapshot. Losing the dynamic additions is bad; losing
            // the configured baseline too, because the database blinked, is worse.
            log.warn("Could not refresh blocked domains, keeping {} entries: {}", snapshot.size(), e.toString());
        }
    }

    @Override
    public Reputation check(String url) {
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (RuntimeException e) {
            return Reputation.unknown("target host could not be parsed");
        }
        if (host == null) {
            return Reputation.unknown("target has no host");
        }

        String normalized = host.toLowerCase(Locale.ROOT);
        Set<String> current = snapshot;

        // Blocking a domain blocks everything under it. Listing "evil.example" and having
        // "login.evil.example" sail through would make the control trivially sidestepped.
        for (String blocked : current) {
            if (normalized.equals(blocked) || normalized.endsWith("." + blocked)) {
                return Reputation.blocked("destination host is on the blocklist");
            }
        }
        return Reputation.clean();
    }
}
