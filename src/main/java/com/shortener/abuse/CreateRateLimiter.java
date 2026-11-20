package com.shortener.abuse;

import com.shortener.config.AbuseProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Limits link creation per API key, or per client IP for anonymous callers (ADR-0009).
 * Admin keys are not limited. Redirects are never limited here (scenario 3, Q3).
 *
 * <p>State is in memory, so each instance enforces its own limit: with N instances behind a load
 * balancer the effective limit is up to N times higher. Correct for the single-instance
 * deployment; a shared store (Redis) is the upgrade path (IMP-002).
 */
@Component
public class CreateRateLimiter {

    private static final long HOUR_NANOS = TimeUnit.HOURS.toNanos(1);

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final int anonymousPerHour;
    private final int keyPerHour;
    private final LongSupplier nanoTime;
    private final Counter rejectedAnonymous;
    private final Counter rejectedKey;

    @Autowired
    public CreateRateLimiter(AbuseProperties props, MeterRegistry meters) {
        this(props, meters, System::nanoTime);
    }

    CreateRateLimiter(AbuseProperties props, MeterRegistry meters, LongSupplier nanoTime) {
        this.anonymousPerHour = props.anonymousCreatesPerHour();
        this.keyPerHour = props.keyCreatesPerHour();
        this.nanoTime = nanoTime;
        this.rejectedAnonymous = rejected(meters, "anonymous");
        this.rejectedKey = rejected(meters, "key");
    }

    private static Counter rejected(MeterRegistry meters, String tier) {
        return Counter.builder("shortener.ratelimit.rejected")
                .description("Link creations refused by the rate limiter")
                .tag("tier", tier)
                .register(meters);
    }

    /**
     * @param keyId    caller's API key, or null when anonymous (then the client IP is the identity)
     * @param admin    admin keys are not limited
     * @throws RateLimitedException if this caller has no allowance left
     */
    public void check(Long keyId, boolean admin, String clientIp) {
        if (admin) {
            return;
        }
        boolean anonymous = keyId == null;
        String id = anonymous ? "ip:" + clientIp : "key:" + keyId;
        int perHour = anonymous ? anonymousPerHour : keyPerHour;
        long now = nanoTime.getAsLong();

        long waitNanos = buckets.computeIfAbsent(id, k -> new TokenBucket(perHour, HOUR_NANOS, now)).tryTake(now);
        if (waitNanos > 0) {
            (anonymous ? rejectedAnonymous : rejectedKey).increment();
            throw new RateLimitedException(Math.max(1, Duration.ofNanos(waitNanos).toSeconds() + 1));
        }
    }

    /** Bounds memory: forget clients whose bucket has refilled (indistinguishable from a new one). */
    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void evictIdle() {
        long now = nanoTime.getAsLong();
        buckets.values().removeIf(b -> b.isFull(now));
    }

    int trackedClients() {
        return buckets.size();
    }
}
