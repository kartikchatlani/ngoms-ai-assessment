package com.shortener.abuse;

/**
 * Classic token bucket: holds up to {@code capacity} tokens and refills continuously at
 * {@code capacity} tokens per {@code periodNanos}. Each create spends one token. That allows a
 * burst up to the full allowance, then a steady rate, rather than a hard reset every hour
 * (which would let a script spend 2x the allowance around the boundary).
 *
 * <p>Time is passed in, so tests drive it without sleeping.
 */
final class TokenBucket {

    private final double capacity;
    private final double tokensPerNano;
    private double tokens;
    private long lastNanos;

    TokenBucket(int capacity, long periodNanos, long nowNanos) {
        this.capacity = capacity;
        this.tokensPerNano = capacity / (double) periodNanos;
        this.tokens = capacity;
        this.lastNanos = nowNanos;
    }

    /** @return 0 if a token was taken, otherwise nanoseconds until one will be available */
    synchronized long tryTake(long nowNanos) {
        refill(nowNanos);
        if (tokens >= 1) {
            tokens -= 1;
            return 0;
        }
        return (long) Math.ceil((1 - tokens) / tokensPerNano);
    }

    /** Full buckets carry no state worth keeping: an evicted bucket is recreated full. */
    synchronized boolean isFull(long nowNanos) {
        refill(nowNanos);
        return tokens >= capacity;
    }

    private void refill(long nowNanos) {
        long elapsed = nowNanos - lastNanos;
        if (elapsed > 0) {
            tokens = Math.min(capacity, tokens + elapsed * tokensPerNano);
            lastNanos = nowNanos;
        }
    }
}
