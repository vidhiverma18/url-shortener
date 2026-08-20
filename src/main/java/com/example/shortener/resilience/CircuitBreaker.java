package com.example.shortener.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Stops a hung dependency from becoming an outage.
 *
 * <p>Timeouts alone are not enough, and the difference is measurable. A dependency that is
 * <em>down</em> refuses connections instantly and costs almost nothing; a dependency that is
 * <em>hung</em> — alive, accepting TCP, never answering — costs a full timeout on every
 * request, forever. In this service that was 412ms per redirect against 1.7ms healthy, and
 * throughput fell from about 14,000 requests per second to 221. A timeout bounds a single
 * request. Only a breaker bounds the <em>pattern</em>.
 *
 * <p>Deliberately small and dependency-free. One call site pair, one failure mode, and a
 * hot path where the closed-state check has to be nearly free.
 *
 * <p><b>Closed</b> after {@code failureThreshold} consecutive failures becomes <b>open</b>
 * for {@code cooldown}, during which calls are refused without being attempted. When the
 * cooldown elapses a single probe is admitted: if it succeeds the breaker closes, and if it
 * fails the cooldown restarts. Admitting exactly one probe matters — releasing the full
 * flood at a dependency that is still sick is how a recovering system gets knocked back
 * down.
 */
public final class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);
    private static final long CLOSED = 0L;

    private final String name;
    private final int failureThreshold;
    private final long cooldownMillis;
    private final LongSupplier clock;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicBoolean probeInFlight = new AtomicBoolean();

    /** Epoch millis at which a probe may be attempted; {@link #CLOSED} means closed. */
    private volatile long retryAt = CLOSED;

    public CircuitBreaker(String name, int failureThreshold, Duration cooldown) {
        this(name, failureThreshold, cooldown, System::currentTimeMillis);
    }

    CircuitBreaker(String name, int failureThreshold, Duration cooldown, LongSupplier clock) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.cooldownMillis = cooldown.toMillis();
        this.clock = clock;
    }

    /** @return false when the call must be skipped entirely. */
    public boolean allowRequest() {
        long until = retryAt;
        if (until == CLOSED) {
            return true;
        }
        if (clock.getAsLong() < until) {
            return false;
        }
        // Cooldown elapsed. Exactly one caller wins the probe; everyone else keeps failing
        // fast until that probe reports back.
        return probeInFlight.compareAndSet(false, true);
    }

    public void recordSuccess() {
        if (retryAt != CLOSED) {
            retryAt = CLOSED;
            consecutiveFailures.set(0);
            probeInFlight.set(false);
            log.info("Circuit breaker '{}' closed: {} is answering again", name, name);
            return;
        }
        // Read before write. On the healthy path this is a cached volatile read and no
        // store at all, which keeps hundreds of concurrent threads off one cache line.
        if (consecutiveFailures.get() != 0) {
            consecutiveFailures.set(0);
        }
    }

    public void recordFailure() {
        probeInFlight.set(false);
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold) {
            boolean wasClosed = retryAt == CLOSED;
            retryAt = clock.getAsLong() + cooldownMillis;
            if (wasClosed) {
                log.warn("Circuit breaker '{}' opened after {} consecutive failures; "
                        + "calls will be skipped for {}ms", name, failures, cooldownMillis);
            }
        }
    }

    /** True when calls are currently being skipped. Exposed for tests and diagnostics. */
    public boolean isOpen() {
        return retryAt != CLOSED;
    }
}
