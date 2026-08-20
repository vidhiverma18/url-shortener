package com.example.shortener.service;

import com.example.shortener.domain.ClickEvent;
import com.example.shortener.repository.ClickEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The analytics buffer is the component whose failure modes matter most, because it
 * sits next to the redirect path. These tests pin the two behaviours that protect it:
 * shed load rather than block, and swallow database failures rather than propagate.
 */
class ClickRecorderTest {

    private final ClickEventRepository repository = mock(ClickEventRepository.class);
    private final ClickRecorder recorder = new ClickRecorder(repository);

    @Test
    void bufferedEventsArePersistedOnFlush() {
        recorder.record(event(1L));
        recorder.record(event(2L));
        assertThat(recorder.getBufferedEvents()).isEqualTo(2);

        recorder.flush();

        verify(repository).saveAll(anyIterable());
        assertThat(recorder.getBufferedEvents()).isZero();
    }

    @Test
    void flushIsANoOpWhenNothingIsBuffered() {
        recorder.flush();
        verify(repository, never()).saveAll(anyIterable());
    }

    @Test
    @DisplayName("recording never blocks: once the buffer is full, events are shed and counted")
    void overflowShedsLoadInsteadOfBlocking() {
        // Capacity is 10_000; push past it and confirm the calls still return promptly
        // and report the loss rather than applying back-pressure to the redirect path.
        for (int i = 0; i < 10_500; i++) {
            recorder.record(event(1L));
        }

        assertThat(recorder.getBufferedEvents()).isEqualTo(10_000);
        assertThat(recorder.getDroppedEvents()).isEqualTo(500);
    }

    @Test
    @DisplayName("a database failure abandons the batch instead of propagating")
    void persistFailureIsContained() {
        when(repository.saveAll(anyIterable())).thenThrow(new RuntimeException("connection reset"));

        recorder.record(event(1L));
        recorder.flush();

        assertThat(recorder.getDroppedEvents()).isEqualTo(1);
        assertThat(recorder.getBufferedEvents()).isZero();
    }

    @Test
    void shutdownDrainsTheRemainingBuffer() {
        Mockito.reset(repository);
        recorder.record(event(7L));

        recorder.drainOnShutdown();

        verify(repository).saveAll(anyIterable());
    }

    private ClickEvent event(long linkId) {
        return new ClickEvent(linkId, Instant.now(), "example.org", "agent", "hash");
    }
}
