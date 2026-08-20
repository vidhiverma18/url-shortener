package com.example.shortener.repository;

import com.example.shortener.domain.ClickEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {

    long countByShortLinkId(Long shortLinkId);

    long countByShortLinkIdAndOccurredAtAfter(Long shortLinkId, Instant since);

    @Query(value = """
            SELECT CAST(DATE_TRUNC('day', occurred_at) AS DATE) AS day, COUNT(*) AS clicks
            FROM click_events
            WHERE short_link_id = :linkId AND occurred_at >= :since
            GROUP BY 1
            ORDER BY 1 DESC
            """, nativeQuery = true)
    List<DailyCount> findDailyCounts(@Param("linkId") Long linkId, @Param("since") Instant since);

    @Query(value = """
            SELECT COALESCE(referrer_host, 'direct') AS source, COUNT(*) AS clicks
            FROM click_events
            WHERE short_link_id = :linkId
            GROUP BY 1
            ORDER BY 2 DESC
            LIMIT 10
            """, nativeQuery = true)
    List<SourceCount> findTopReferrers(@Param("linkId") Long linkId);

    @Query(value = """
            SELECT COUNT(DISTINCT visitor_hash)
            FROM click_events
            WHERE short_link_id = :linkId AND visitor_hash IS NOT NULL
            """, nativeQuery = true)
    long countDistinctVisitors(@Param("linkId") Long linkId);

    interface DailyCount {
        java.sql.Date getDay();

        long getClicks();
    }

    interface SourceCount {
        String getSource();

        long getClicks();
    }
}
