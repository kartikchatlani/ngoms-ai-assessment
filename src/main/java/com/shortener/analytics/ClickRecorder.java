package com.shortener.analytics;

import com.shortener.config.AnalyticsProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Records clicks without slowing down redirects (ADR-0006).
 *
 * <p>The redirect thread only does a non-blocking {@code offer} into a bounded in-memory queue.
 * One background writer drains the queue and inserts in batches: when traffic is light each click
 * is written almost immediately; under load, clicks pile up during each insert and the next batch
 * is bigger, so database round trips grow much more slowly than traffic.
 *
 * <p>Delivery is <b>at-most-once</b>: a full buffer drops new clicks, a failed write drops that
 * batch, and a crash loses what is buffered. Every drop is counted in the
 * {@code shortener.clicks} metric. Graceful shutdown loses nothing (see {@link #getPhase()}).
 */
@Component
public class ClickRecorder implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ClickRecorder.class);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(200);

    private final ClickRepository repository;
    private final Clock clock;
    private final boolean enabled;
    private final int batchSize;
    private final BlockingQueue<ClickEvent> queue;

    /** Accepted but not yet written (buffered + in the batch being written). */
    private final AtomicInteger pending = new AtomicInteger();

    private final Counter recorded;
    private final Counter droppedBufferFull;
    private final Counter droppedWriteFailed;

    private volatile boolean running;
    private Thread writer;

    public ClickRecorder(ClickRepository repository, Clock clock, AnalyticsProperties props, MeterRegistry meters) {
        this.repository = repository;
        this.clock = clock;
        this.enabled = props.enabled();
        this.batchSize = props.batchSize();
        this.queue = new ArrayBlockingQueue<>(props.bufferCapacity());

        this.recorded = outcome(meters, "recorded");
        this.droppedBufferFull = outcome(meters, "dropped_buffer_full");
        this.droppedWriteFailed = outcome(meters, "dropped_write_failed");
        Gauge.builder("shortener.clicks.pending", pending, AtomicInteger::get)
                .description("Clicks accepted but not yet written to the database")
                .register(meters);
    }

    private static Counter outcome(MeterRegistry meters, String outcome) {
        return Counter.builder("shortener.clicks")
                .description("Redirects offered to click analytics, by outcome")
                .tag("outcome", outcome)
                .register(meters);
    }

    /**
     * Called on the redirect path. Never blocks and never throws: analytics must not be able to
     * break or slow a redirect.
     */
    public void record(long linkId, String referer, String userAgent) {
        if (!enabled) {
            return;
        }
        try {
            ClickEvent event = new ClickEvent(linkId, clock.instant(),
                    ClickClassifier.referrerHost(referer), ClickClassifier.agentClass(userAgent));
            pending.incrementAndGet();
            if (!queue.offer(event)) {
                pending.decrementAndGet();
                droppedBufferFull.increment();
            }
        } catch (RuntimeException e) {
            log.warn("Failed to record click for link {}", linkId, e);
        }
    }

    // ---- background writer -----------------------------------------------------------------

    private void writeLoop() {
        List<ClickEvent> batch = new ArrayList<>(batchSize);
        while (running) {
            try {
                ClickEvent first = queue.poll(POLL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue; // idle; re-check `running`
                }
                batch.add(first);
                queue.drainTo(batch, batchSize - 1);
                write(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                batch.clear();
            }
        }
    }

    private void write(List<ClickEvent> batch) {
        try {
            repository.insertBatch(batch);
            recorded.increment(batch.size());
        } catch (RuntimeException e) {
            // No retry: retrying during an outage would hold memory and delay newer clicks.
            // The loss is visible in metrics (ADR-0006).
            droppedWriteFailed.increment(batch.size());
            log.warn("Dropped {} clicks: write failed ({})", batch.size(), e.toString());
        } finally {
            pending.addAndGet(-batch.size());
        }
    }

    /** Writes everything still buffered, on the calling thread. */
    private void drainRemaining() {
        List<ClickEvent> batch = new ArrayList<>(batchSize);
        while (queue.drainTo(batch, batchSize) > 0) {
            write(batch);
            batch.clear();
        }
    }

    /**
     * Waits until every click accepted so far has been written (or dropped).
     * For tests and diagnostics; the redirect path never calls this.
     *
     * @return false if that did not happen within the timeout
     */
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (pending.get() > 0) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(5);
        }
        return true;
    }

    // ---- lifecycle -------------------------------------------------------------------------

    @Override
    public synchronized void start() {
        running = true;
        writer = Thread.ofPlatform().name("click-writer").daemon(true).start(this::writeLoop);
    }

    @Override
    public synchronized void stop() {
        if (writer == null) {
            return;
        }
        running = false;
        try {
            // No interrupt: interrupting a thread inside a JDBC call can break the connection.
            // The writer notices `running == false` within one poll timeout.
            writer.join(Duration.ofSeconds(10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        drainRemaining();
        log.info("Click recorder stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Lifecycles stop in descending phase order. The web server's graceful shutdown runs at
     * {@code DEFAULT_PHASE - 1024} and the server itself stops at {@code DEFAULT_PHASE - 2048}.
     * Phase 0 is far below both, so this stops only after the last in-flight redirect has
     * finished, and its click is still written. The DataSource is a plain bean, closed after
     * all lifecycles have stopped.
     */
    @Override
    public int getPhase() {
        return 0;
    }
}
