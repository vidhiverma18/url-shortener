package com.example.shortener.repository;

import com.example.shortener.domain.ShortLink;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
