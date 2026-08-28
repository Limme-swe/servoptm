package io.github.limmeswe.headroom.core;

/**
 * Allocation-free token bucket with a caller-provided monotonic clock.
 */
public final class TokenBucket {

    private double ratePerSecond;
    private double capacity;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(double ratePerSecond, double capacity, long nowNanos) {
        reconfigure(ratePerSecond, capacity, nowNanos);
        tokens = capacity;
    }

    public void reconfigure(double newRatePerSecond, double newCapacity, long nowNanos) {
        if (!Double.isFinite(newRatePerSecond) || newRatePerSecond < 0.0) {
            throw new IllegalArgumentException("ratePerSecond must be finite and non-negative");
        }
        if (!Double.isFinite(newCapacity) || newCapacity < 0.0) {
            throw new IllegalArgumentException("capacity must be finite and non-negative");
        }
        refill(nowNanos);
        ratePerSecond = newRatePerSecond;
        capacity = newCapacity;
        tokens = Math.min(tokens, capacity);
        lastRefillNanos = nowNanos;
    }

    public boolean tryConsume(double amount, long nowNanos) {
        if (!Double.isFinite(amount) || amount <= 0.0) {
            throw new IllegalArgumentException("amount must be finite and positive");
        }
        refill(nowNanos);
        if (tokens + 1.0e-9 < amount) {
            return false;
        }
        tokens -= amount;
        return true;
    }

    public double available(long nowNanos) {
        refill(nowNanos);
        return tokens;
    }

    private void refill(long nowNanos) {
        if (lastRefillNanos == 0L) {
            lastRefillNanos = nowNanos;
            return;
        }
        long elapsed = Math.max(0L, nowNanos - lastRefillNanos);
        if (elapsed == 0L) {
            return;
        }
        tokens = Math.min(capacity, tokens + elapsed * ratePerSecond / 1_000_000_000.0);
        lastRefillNanos = nowNanos;
    }
}
