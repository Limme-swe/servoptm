package io.github.limmeswe.headroom.core;

import java.util.Arrays;

/**
 * Fixed-allocation rolling window for latency samples.
 */
public final class SlidingPercentileWindow {

    private final double[] samples;
    private int cursor;
    private int size;

    public SlidingPercentileWindow(int capacity) {
        if (capacity < 2) {
            throw new IllegalArgumentException("capacity must be at least 2");
        }
        samples = new double[capacity];
    }

    public void add(double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException("sample must be finite and non-negative");
        }
        samples[cursor] = value;
        cursor = (cursor + 1) % samples.length;
        size = Math.min(samples.length, size + 1);
    }

    public Snapshot snapshot() {
        if (size == 0) {
            return new Snapshot(0, 0.0, 0.0, 0.0, 0.0);
        }
        double[] copy = Arrays.copyOf(samples, size);
        Arrays.sort(copy);
        double sum = 0.0;
        for (double value : copy) {
            sum += value;
        }
        return new Snapshot(
                size,
                sum / size,
                percentile(copy, 0.50),
                percentile(copy, 0.95),
                percentile(copy, 0.99)
        );
    }

    private static double percentile(double[] sorted, double percentile) {
        if (sorted.length == 1) {
            return sorted[0];
        }
        double index = percentile * (sorted.length - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        if (lower == upper) {
            return sorted[lower];
        }
        double fraction = index - lower;
        return sorted[lower] + (sorted[upper] - sorted[lower]) * fraction;
    }

    public record Snapshot(int samples, double mean, double p50, double p95, double p99) {
    }
}
