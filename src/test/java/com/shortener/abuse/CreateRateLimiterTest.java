package com.shortener.abuse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shortener.config.AbuseProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Token-bucket behaviour of ADR-0009, with a fake clock (no sleeping). */
class CreateRateLimiterTest {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final CreateRateLimiter limiter = new CreateRateLimiter(
            new AbuseProperties(10, 1000, List.of(), 30), meters, nanos::get);

    @Test
    void anonymousCallerGetsTenPerHourThenA429WithRetryAfter() {
        for (int i = 0; i < 10; i++) {
            limiter.check(null, false, "203.0.113.7");
        }
        assertThatThrownBy(() -> limiter.check(null, false, "203.0.113.7"))
                .isInstanceOfSatisfying(RateLimitedException.class,
                        e -> assertThat(e.retryAfterSeconds()).isBetween(1L, 361L)); // one token = 6 minutes
        assertThat(meters.counter("shortener.ratelimit.rejected", "tier", "anonymous").count()).isEqualTo(1);
    }

    @Test
    void allowanceRefillsGraduallyNotAllAtOnce() {
        for (int i = 0; i < 10; i++) {
            limiter.check(null, false, "203.0.113.7");
        }
        advance(TimeUnit.MINUTES.toNanos(6)); // exactly one token's worth
        assertThatCode(() -> limiter.check(null, false, "203.0.113.7")).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.check(null, false, "203.0.113.7")).isInstanceOf(RateLimitedException.class);
    }

    @Test
    void eachIpHasItsOwnBucket() {
        for (int i = 0; i < 10; i++) {
            limiter.check(null, false, "203.0.113.7");
        }
        assertThatCode(() -> limiter.check(null, false, "198.51.100.1")).doesNotThrowAnyException();
    }

    @Test
    void keysGetTheirOwnHigherLimitRegardlessOfIp() {
        // Same office IP as an exhausted anonymous user (NAT, scenario 3 risk register).
        for (int i = 0; i < 10; i++) {
            limiter.check(null, false, "203.0.113.7");
        }
        for (int i = 0; i < 1000; i++) {
            limiter.check(42L, false, "203.0.113.7");
        }
        assertThatThrownBy(() -> limiter.check(42L, false, "203.0.113.7")).isInstanceOf(RateLimitedException.class);
        assertThat(meters.counter("shortener.ratelimit.rejected", "tier", "key").count()).isEqualTo(1);
    }

    @Test
    void adminIsNeverLimited() {
        for (int i = 0; i < 5000; i++) {
            limiter.check(1L, true, "203.0.113.7");
        }
    }

    @Test
    void idleClientsAreForgottenSoMemoryStaysBounded() {
        limiter.check(null, false, "203.0.113.7");
        limiter.check(null, false, "198.51.100.1");
        assertThat(limiter.trackedClients()).isEqualTo(2);

        advance(TimeUnit.HOURS.toNanos(1)); // both buckets refilled: no state worth keeping
        limiter.evictIdle();

        assertThat(limiter.trackedClients()).isZero();
    }

    @Test
    void evictionKeepsClientsWhoAreStillLimited() {
        for (int i = 0; i < 10; i++) {
            limiter.check(null, false, "203.0.113.7");
        }
        limiter.evictIdle();
        // Forgetting this client now would hand it a fresh allowance.
        assertThatThrownBy(() -> limiter.check(null, false, "203.0.113.7")).isInstanceOf(RateLimitedException.class);
    }

    private void advance(long n) {
        nanos.addAndGet(n);
    }
}
