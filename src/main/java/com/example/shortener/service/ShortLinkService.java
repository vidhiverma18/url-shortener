package com.example.shortener.service;

import com.example.shortener.domain.ClickEvent;
import com.example.shortener.domain.ShortLink;
import com.example.shortener.repository.ClickEventRepository;
import com.example.shortener.repository.IdAllocator;
import com.example.shortener.repository.ShortLinkRepository;
import com.example.shortener.service.error.AliasAlreadyTakenException;
import com.example.shortener.service.error.LinkNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Application service for the link lifecycle. Holds the ordering rules that the
 * controllers must not be trusted with: validate before allocate, persist before
 * cache, and never let analytics failures reach the caller.
 */
@Service
public class ShortLinkService {

    private final ShortLinkRepository links;
    private final ClickEventRepository clicks;
    private final IdAllocator idAllocator;
    private final ShortCodeFactory codeFactory;
    private final UrlValidator urlValidator;
    private final LinkCache cache;
    private final ClickRecorder clickRecorder;

    public ShortLinkService(ShortLinkRepository links,
                            ClickEventRepository clicks,
                            IdAllocator idAllocator,
                            ShortCodeFactory codeFactory,
                            UrlValidator urlValidator,
                            LinkCache cache,
                            ClickRecorder clickRecorder) {
        this.links = links;
        this.clicks = clicks;
        this.idAllocator = idAllocator;
        this.codeFactory = codeFactory;
        this.urlValidator = urlValidator;
        this.cache = cache;
        this.clickRecorder = clickRecorder;
    }

    @Transactional
    public ShortLink create(String rawUrl, String requestedAlias, Instant expiresAt, String createdBy) {
        // Validation first: an invalid request must not burn a sequence value, or the
        // id space becomes a record of how often people typo a URL.
        String url = urlValidator.validateAndNormalize(rawUrl);

        boolean custom = requestedAlias != null && !requestedAlias.isBlank();
        long id = idAllocator.nextId();
        String code = custom ? codeFactory.validateAlias(requestedAlias.trim()) : codeFactory.fromId(id);

        if (custom && links.existsByCode(code)) {
            throw new AliasAlreadyTakenException(code);
        }

        ShortLink link = new ShortLink(id, code, url, custom, createdBy, expiresAt);
        try {
            return links.saveAndFlush(link);
        } catch (DataIntegrityViolationException e) {
            // Two concurrent requests for the same alias both pass existsByCode; the
            // unique index is the actual arbiter and this converts its verdict into a
            // 409 instead of a 500.
            throw new AliasAlreadyTakenException(code);
        }
    }

    /**
     * Resolves a code for redirection. Reads the cache first, falls through to the
     * database, and records the outcome in the cache either way.
     *
     * <p>Deliberately <b>not</b> {@code @Transactional}. Spring begins the transaction
     * before the method body runs, which borrows a connection from the pool ahead of the
     * cache lookup; with the database down, a cache hit then failed with a 500 after
     * blocking for the full connection timeout. The repository call below carries its own
     * read-only transaction, so the database path is still correctly scoped while a cache
     * hit now touches no connection at all.
     */
    public Resolution resolve(String code) {
        Optional<LinkCache.CacheEntry> cached = cache.lookup(code);
        if (cached.isPresent()) {
            LinkCache.CacheEntry entry = cached.get();
            if (entry.knownMiss()) {
                throw new LinkNotFoundException(code);
            }
            // Cached entries never outlive the link's expiry (the TTL is capped at
            // creation), so a hit is safe to serve without re-checking resolvability.
            return new Resolution(entry.linkId(), entry.url(), true);
        }

        ShortLink link = links.findByCode(code).orElse(null);
        if (link == null || !link.isResolvable(Instant.now())) {
            cache.putMiss(code);
            throw new LinkNotFoundException(code);
        }
        cache.put(link);
        return new Resolution(link.getId(), link.getOriginalUrl(), false);
    }

    /** Fire-and-forget. Never throws: a redirect must not fail because analytics did. */
    public void recordClick(long linkId, String referrerHost, String userAgent, String visitorHash) {
        clickRecorder.record(new ClickEvent(linkId, Instant.now(), referrerHost, userAgent, visitorHash));
    }

    @Transactional
    public void deactivate(String code) {
        ShortLink link = links.findByCode(code).orElseThrow(() -> new LinkNotFoundException(code));
        link.deactivate();
        links.save(link);
        // Evict after the write so a concurrent read cannot repopulate the cache from
        // the pre-deactivation state.
        cache.evict(code);
    }

    @Transactional(readOnly = true)
    public ShortLink require(String code) {
        return links.findByCode(code).orElseThrow(() -> new LinkNotFoundException(code));
    }

    @Transactional(readOnly = true)
    public LinkStats stats(String code, int windowDays) {
        ShortLink link = require(code);
        Instant since = Instant.now().minusSeconds(windowDays * 86_400L);
        return new LinkStats(
                link,
                clicks.countByShortLinkId(link.getId()),
                clicks.countDistinctVisitors(link.getId()),
                clicks.countByShortLinkIdAndOccurredAtAfter(link.getId(), Instant.now().minusSeconds(86_400)),
                clicks.findDailyCounts(link.getId(), since),
                clicks.findTopReferrers(link.getId()));
    }

    public record Resolution(long linkId, String url, boolean fromCache) {
    }

    public record LinkStats(ShortLink link,
                            long totalClicks,
                            long uniqueVisitors,
                            long clicksLast24Hours,
                            java.util.List<ClickEventRepository.DailyCount> daily,
                            java.util.List<ClickEventRepository.SourceCount> topReferrers) {
    }
}
