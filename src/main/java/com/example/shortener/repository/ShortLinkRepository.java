package com.example.shortener.repository;

import com.example.shortener.domain.ShortLink;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ShortLinkRepository extends JpaRepository<ShortLink, Long> {

    Optional<ShortLink> findByCode(String code);

    boolean existsByCode(String code);

    /**
     * Finds this owner's reusable link for a URL, served by the partial unique index on
     * {@code (created_by, url_hash) WHERE url_hash IS NOT NULL AND active}.
     *
     * <p>Ordered by id purely for determinism. The index makes more than one match
     * impossible, but ordering means that if it ever did happen — a migration gap, a
     * manually inserted row — every caller still gets the same answer instead of whichever
     * row the planner reached first.
     */
    Optional<ShortLink> findFirstByCreatedByAndUrlHashAndActiveTrueOrderByIdAsc(
            String createdBy, String urlHash);

    /**
     * Live links whose screening is stale or absent, oldest first.
     *
     * <p>{@code NULLS FIRST} puts never-screened links at the head of the queue: those are
     * the ones admitted while a provider was unreachable, so they are the least trustworthy
     * and the most urgent. Served by the partial index created in V5.
     */
    @Query("""
            SELECT l FROM ShortLink l
            WHERE l.active = true
              AND (l.screenedAt IS NULL OR l.screenedAt < :cutoff)
            ORDER BY l.screenedAt ASC NULLS FIRST
            """)
    List<ShortLink> findRescanCandidates(@Param("cutoff") Instant cutoff, Pageable pageable);
}
