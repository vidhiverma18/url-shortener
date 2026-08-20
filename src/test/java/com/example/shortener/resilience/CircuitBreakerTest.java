package com.example.shortener.resilience;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The breaker is driven by a fake clock rather than by sleeping. Time-based tests that
 * sleep are slow and flaky under a loaded CI machine, and the thing under test here is a
 * state machine, not a timer.
 */
class CircuitBreakerTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);

    private CircuitBreaker breaker(int threshold, Duration cooldown) {
        return new CircuitBreaker("test", threshold, cooldown, now::get);
    }

    @Test
    void allowsRequestsWhileHealthy() {
        CircuitBreaker breaker = breaker(3, Duration.ofSeconds(5));
        for (int i = 0; i < 100; i++) {
            assertThat(breaker.allowRequest()).isTrue();
            breaker.recordSuccess();
        }
        assertThat(breaker.isOpen()).isFalse();
    }

    @Test
    @DisplayName("opens only after the threshold of consecutive failures")
    void opensAtThreshold() {
        CircuitBreaker breaker = breaker(3, Duration.ofSeconds(5));

        breaker.recordFailure();
        breaker.recordFailure();
        assertThat(breaker.isOpen()).isFalse();
        assertThat(breaker.allowRequest()).isTrue();

        breaker.recordFailure();
        assertThat(breaker.isOpen()).isTrue();
        assertThat(breaker.allowRequest()).isFalse();
    }

    @Test
    @DisplayName("an intervening success resets the count, so scattered failures never open it")
    void successResetsTheFailureCount() {
        CircuitBreaker breaker = breaker(3, Duration.ofSeconds(5));

        for (int i = 0; i < 50; i++) {
            breaker.recordFailure();
            breaker.recordFailure();
            breaker.recordSuccess();
        }
        assertThat(breaker.isOpen()).isFalse();
    }

    @Test
    @DisplayName("while open, calls are refused without being attempted")
    void refusesEverythingDuringCooldown() {
        CircuitBreaker breaker = breaker(1, Duration.ofSeconds(5));
        breaker.recordFailure();

        now.addAndGet(4_999);
        for (int i = 0; i < 100; i++) {
            assertThat(breaker.allowRequest()).isFalse();
        }
    }

    @Test
    @DisplayName("after the cooldown exactly one probe is admitted, not the whole flood")
    void admitsASingleProbe() {
        CircuitBreaker breaker = breaker(1, Duration.ofSeconds(5));
        breaker.recordFailure();
        now.addAndGet(5_001);

        assertThat(breaker.allowRequest()).as("first caller wins the probe").isTrue();
        // This is the property that matters: releasing everything at a dependency that may
        // still be sick is how a recovering system gets knocked straight back down.
        for (int i = 0; i < 100; i++) {
            assertThat(breaker.allowRequest()).as("everyone else still fails fast").isFalse();
        }
    }

    @Test
    @DisplayName("a failed probe restarts the cooldown rather than closing the breaker")
    void failedProbeReopens() {
        CircuitBreaker breaker = breaker(1, Duration.ofSeconds(5));
        breaker.recordFailure();
        now.addAndGet(5_001);

        assertThat(breaker.allowRequest()).isTrue();
        breaker.recordFailure();

        assertThat(breaker.isOpen()).isTrue();
        assertThat(breaker.allowRequest()).isFalse();

        now.addAndGet(5_001);
        assertThat(breaker.allowRequest()).as("a fresh probe after the new cooldown").isTrue();
    }

    @Test
    @DisplayName("a successful probe closes the breaker and restores full traffic")
    void successfulProbeCloses() {
        CircuitBreaker breaker = breaker(1, Duration.ofSeconds(5));
        breaker.recordFailure();
        now.addAndGet(5_001);

        assertThat(breaker.allowRequest()).isTrue();
        breaker.recordSuccess();

        assertThat(breaker.isOpen()).isFalse();
        for (int i = 0; i < 100; i++) {
            assertThat(breaker.allowRequest()).isTrue();
        }
    }

    @Test
    @DisplayName("under concurrency the cooldown still admits exactly one probe")
    void onlyOneProbeUnderConcurrency() throws Exception {
        CircuitBreaker breaker = breaker(1, Duration.ofSeconds(5));
        breaker.recordFailure();
        now.addAndGet(5_001);

        int threads = 64;
        AtomicInteger admitted = new AtomicInteger();
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.execute(() -> {
                try {
                    startLine.await();
                    if (breaker.allowRequest()) {
                        admitted.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            });
        }
        startLine.countDown();
        assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(admitted.get())
                .as("a compare-and-set gate, not a check-then-act race")
                .isEqualTo(1);
    }
}
