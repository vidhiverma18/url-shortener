package com.example.shortener.api.dto;

import com.example.shortener.service.ShortLinkService;

import java.time.LocalDate;
import java.util.List;

public record LinkStatsResponse(String code,
                                String shortUrl,
                                String originalUrl,
                                long totalClicks,
                                long uniqueVisitors,
                                long clicksLast24Hours,
                                List<DailyClicks> daily,
                                List<ReferrerClicks> topReferrers,
                                String accuracyNote) {

    /**
     * Counts are eventually consistent by design; the buffer flushes roughly once a
     * second and sheds load under pressure. Saying so in the payload keeps a caller
     * from treating these numbers as billing-grade.
     */
    private static final String ACCURACY_NOTE =
            "Click counts are best-effort and eventually consistent; recent clicks may lag by a few seconds.";

    public static LinkStatsResponse from(ShortLinkService.LinkStats stats, String baseUrl) {
        return new LinkStatsResponse(
                stats.link().getCode(),
                baseUrl + "/" + stats.link().getCode(),
                stats.link().getOriginalUrl(),
                stats.totalClicks(),
                stats.uniqueVisitors(),
                stats.clicksLast24Hours(),
                stats.daily().stream()
                        .map(d -> new DailyClicks(d.getDay().toLocalDate(), d.getClicks()))
                        .toList(),
                stats.topReferrers().stream()
                        .map(r -> new ReferrerClicks(r.getSource(), r.getClicks()))
                        .toList(),
                ACCURACY_NOTE);
    }

    public record DailyClicks(LocalDate day, long clicks) {
    }

    public record ReferrerClicks(String source, long clicks) {
    }
}
