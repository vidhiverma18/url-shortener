package com.example.shortener.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.math.BigInteger;

/**
 * Allocates the monotonic ids that short codes are derived from.
 *
 * <p>Isolated behind its own component because id allocation is the one part of the
 * write path with a plausible future migration: if a single database sequence ever
 * becomes the bottleneck, this is the seam where a Snowflake generator or a batched
 * range allocator drops in without touching the service layer.
 */
@Component
public class IdAllocator {

    @PersistenceContext
    private EntityManager entityManager;

    public long nextId() {
        Object value = entityManager
                .createNativeQuery("SELECT nextval('short_link_id_seq')")
                .getSingleResult();
        return switch (value) {
            case BigInteger big -> big.longValue();
            case Number number -> number.longValue();
            default -> throw new IllegalStateException("unexpected sequence type: " + value.getClass());
        };
    }
}
