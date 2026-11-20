package com.shortener.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.shortener.config.AnalyticsProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The redirect-path guarantees of ADR-0006: record() never blocks or throws, overload and
 * database failures drop clicks visibly (metrics) instead of slowing redirects, and a graceful
 * stop writes everything still buffered.
 */
class ClickRecorderTest {

    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final FakeRepository repo = new FakeRepository();
    private ClickRecorder recorder;

    @AfterEach
    void tearDown() {
        repo.release(); // never leave a writer thread parked
        if (recorder != null && recorder.isRunning()) {
            recorder.stop();
        }
    }

    @Test
    void recordedClicksAreWrittenWithClassifiedFields() throws Exception {
        recorder = started(new AnalyticsProperties(true, 100, 10));

        recorder.record(42, "https://News.Example/a?b=c", "curl/8.0");

        assertThat(recorder.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(repo.written).containsExactly(new ClickEvent(42, NOW, "news.example", AgentClass.BOT));
        assertThat(count("recorded")).isEqualTo(1);
    }

    @Test
    void manyClicksAreBatched() throws Exception {
        recorder = started(new AnalyticsProperties(true, 10_000, 100));
        repo.blockWrites(); // let clicks pile up behind a slow first write

        for (int i = 0; i < 1000; i++) {
            recorder.record(i, null, null);
        }
        repo.release();

        assertThat(recorder.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(repo.written).hasSize(1000);
        assertThat(repo.batchSizes).allMatch(size -> size <= 100);
        assertThat(repo.batchSizes.size()).as("round trips").isLessThan(1000 / 10);
    }

    @Test
    void fullBufferDropsClicksInsteadOfBlocking() throws Exception {
        recorder = started(new AnalyticsProperties(true, 5, 1));
        repo.blockWrites(); // writer is stuck: nothing leaves the buffer

        long start = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            recorder.record(i, null, null);
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(elapsedMs).as("record() must not wait for the database").isLessThan(1000);
        assertThat(count("dropped_buffer_full")).isGreaterThanOrEqualTo(44); // 50 - 5 buffered - 1 in flight
        repo.release();
        assertThat(recorder.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(count("recorded") + count("dropped_buffer_full")).isEqualTo(50);
    }

    @Test
    void writeFailureDropsTheBatchAndTheWriterKeepsGoing() throws Exception {
        recorder = started(new AnalyticsProperties(true, 100, 10));
        repo.failNextWrites(1);

        recorder.record(1, null, null);
        assertThat(recorder.awaitIdle(Duration.ofSeconds(5))).isTrue();
        recorder.record(2, null, null);
        assertThat(recorder.awaitIdle(Duration.ofSeconds(5))).isTrue();

        assertThat(count("dropped_write_failed")).isEqualTo(1);
        assertThat(repo.written).extracting(ClickEvent::linkId).containsExactly(2L);
    }

    @Test
    void stopWritesEverythingStillBuffered() {
        recorder = new ClickRecorder(repo, Clock.fixed(NOW, ZoneOffset.UTC), new AnalyticsProperties(true, 100, 10), meters);
        recorder.start();
        repo.blockWrites();
        for (int i = 0; i < 25; i++) {
            recorder.record(i, null, null);
        }
        repo.release();

        recorder.stop(); // graceful shutdown

        assertThat(repo.written).hasSize(25);
        assertThat(recorder.isRunning()).isFalse();
    }

    @Test
    void disabledRecorderIgnoresClicks() throws Exception {
        recorder = started(new AnalyticsProperties(false, 100, 10));

        recorder.record(1, "https://example.com", "Mozilla/5.0");

        assertThat(recorder.awaitIdle(Duration.ofSeconds(1))).isTrue();
        assertThat(repo.written).isEmpty();
        assertThat(count("recorded") + count("dropped_buffer_full")).isZero();
    }

    @Test
    void recordNeverThrowsEvenIfTheClockFails() {
        Clock broken = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { throw new IllegalStateException("boom"); }
        };
        recorder = new ClickRecorder(repo, broken, new AnalyticsProperties(true, 100, 10), meters);

        assertThatCode(() -> recorder.record(1, "x", "y")).doesNotThrowAnyException();
    }

    // ---- helpers -------------------------------------------------------------------------

    private ClickRecorder started(AnalyticsProperties props) {
        ClickRecorder r = new ClickRecorder(repo, Clock.fixed(NOW, ZoneOffset.UTC), props, meters);
        r.start();
        return r;
    }

    private double count(String outcome) {
        var counter = meters.find("shortener.clicks").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    /** In-memory stand-in for the database, with switches to make it slow or failing. */
    static class FakeRepository extends ClickRepository {
        final List<ClickEvent> written = Collections.synchronizedList(new ArrayList<>());
        final List<Integer> batchSizes = Collections.synchronizedList(new ArrayList<>());
        private volatile CountDownLatch gate = new CountDownLatch(0);
        private volatile int failuresLeft;

        FakeRepository() {
            super(null, null);
        }

        void blockWrites() { gate = new CountDownLatch(1); }
        void release() { gate.countDown(); }
        void failNextWrites(int n) { failuresLeft = n; }

        @Override
        public void insertBatch(List<ClickEvent> events) {
            try {
                gate.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (failuresLeft > 0) {
                failuresLeft--;
                throw new org.springframework.dao.DataAccessResourceFailureException("db down");
            }
            batchSizes.add(events.size());
            written.addAll(events);
        }
    }
}
