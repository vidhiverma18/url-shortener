package com.example.shortener.service;

import com.example.shortener.domain.ClickEvent;
import com.example.shortener.repository.ClickEventRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Buffers click events in memory and flushes them in batches.
 *
 * <p>The redirect path must never wait on analytics. Writing one row per redirect
 * inside the request would put an insert, a flush and a commit between the user and
 * their destination, and would couple redirect availability to write availability of
 * the analytics table.
 *
 * <p>The explicit trade-off is durability. Analytics are <em>best effort</em>: events
 * sitting in the buffer are lost if the process is killed, and events are dropped
 * outright when the buffer fills. Both are deliberate. A shortener that stops
 * redirecting because its metrics pipeline is unhealthy has its priorities backwards.
 * Dropped events are counted and logged so the loss is visible rather than silent.
 *
 * <p>An in-process queue is the right size for this prototype, not for production;
 * the production shape is a durable log (Kafka) consumed by a separate aggregator.
 * See docs/decisions/ADR-004-async-analytics.md.
 */
@Component
public class ClickRecorder {

    private static final Logger log = LoggerFactory.getLogger(ClickRecorder.class);
    private static final int BUFFER_CAPACITY = 10_000;
    private static final int MAX_BATCH = 500;

    private final BlockingQueue<ClickEvent> buffer = new ArrayBlockingQueue<>(BUFFER_CAPACITY);
    private final AtomicLong droppedEvents = new AtomicLong();
    private final ClickEventRepository repository;
    private final AbuseMonitor abuseMonitor;

    public ClickRecorder(ClickEventRepository repository, AbuseMonitor abuseMonitor) {
        this.repository = repository;
        this.abuseMonitor = abuseMonitor;
    }

    /** Non-blocking. Returns false when the event was shed because the buffer is full. */
    public boolean record(ClickEvent event) {
        if (buffer.offer(event)) {
            return true;
        }
        long dropped = droppedEvents.incrementAndGet();
        // Log on a curve so a sustained overload does not itself become the outage.
        if (Long.bitCount(dropped) == 1) {
            log.warn("Click analytics buffer full, {} events dropped so far", dropped);
        }
        return false;
    }

    @Scheduled(fixedDelayString = "${shortener.analytics.flush-interval-ms:1000}")
    public void flush() {
        List<ClickEvent> batch = new ArrayList<>(MAX_BATCH);
        buffer.drainTo(batch, MAX_BATCH);
        if (batch.isEmpty()) {
            return;
        }
        persist(batch);
    }

    /**
     * No {@code @Transactional} here on purpose: it would be a self-invocation from
     * {@link #flush()} and the proxy would never apply it. {@code saveAll} carries its
     * own transaction, which also gives each batch an independent failure boundary.
     */
    void persist(List<ClickEvent> batch) {
        // Abuse detection rides on the flush rather than the redirect, so watching traffic
        // for hostile patterns costs the hot path nothing. It runs before the insert and in
        // its own guard: a signal worth acting on should not be lost because the analytics
        // write failed, and it must not be able to break that write either.
        try {
            abuseMonitor.observe(batch.stream().collect(java.util.stream.Collectors.toMap(
                    ClickEvent::getShortLinkId, event -> 1, Integer::sum)));
        } catch (RuntimeException e) {
            log.warn("Abuse observation failed for batch of {}: {}", batch.size(), e.toString());
        }

        try {
            repository.saveAll(batch);
        } catch (RuntimeException e) {
            // Retrying would risk unbounded growth of the buffer behind a broken
            // database, so the batch is abandoned and the loss recorded.
            droppedEvents.addAndGet(batch.size());
            log.error("Failed to persist {} click events, batch abandoned: {}", batch.size(), e.toString());
        }
    }

    public long getDroppedEvents() {
        return droppedEvents.get();
    }

    public int getBufferedEvents() {
        return buffer.size();
    }

    @PreDestroy
    public void drainOnShutdown() {
        List<ClickEvent> remaining = new ArrayList<>();
        buffer.drainTo(remaining);
        if (!remaining.isEmpty()) {
            log.info("Flushing {} buffered click events on shutdown", remaining.size());
            persist(remaining);
        }
    }
}
