package dev.headroom.core;

import java.util.Arrays;

/**
 * Controls admission and the global distance ceiling from tail tick latency.
 */
public final class TailLatencyController {

    public enum Pressure {
        HEALTHY,
        CONSTRAINED,
        CRITICAL
    }

    private final Thresholds thresholds;
    private final double[] samples;

    private int sampleCount;
    private int sampleCursor;
    private int unhealthyStreak;
    private int healthyStreak;
    private int cooldownRemaining;
    private int distancePenalty;
    private Decision decision = new Decision(Pressure.HEALTHY, 0, 0.0, 0.0);

    public TailLatencyController(Thresholds thresholds) {
        this.thresholds = thresholds;
        this.samples = new double[thresholds.sampleWindowTicks()];
    }

    public Decision sample(double tickMillis) {
        if (!Double.isFinite(tickMillis) || tickMillis < 0.0) {
            throw new IllegalArgumentException("tickMillis must be finite and non-negative");
        }

        this.samples[this.sampleCursor] = tickMillis;
        this.sampleCursor = (this.sampleCursor + 1) % this.samples.length;
        this.sampleCount = Math.min(this.samples.length, this.sampleCount + 1);
        if (this.cooldownRemaining > 0) {
            this.cooldownRemaining--;
        }

        double p95 = percentile95();
        double mean = mean();
        Pressure pressure;
        if (tickMillis >= this.thresholds.criticalTickMillis()
                || p95 >= this.thresholds.hardP95Millis()) {
            pressure = Pressure.CRITICAL;
            this.unhealthyStreak++;
            this.healthyStreak = 0;
        } else if (p95 >= this.thresholds.softP95Millis()) {
            pressure = Pressure.CONSTRAINED;
            this.unhealthyStreak++;
            this.healthyStreak = 0;
        } else {
            pressure = Pressure.HEALTHY;
            this.healthyStreak++;
            this.unhealthyStreak = 0;
        }

        if (this.cooldownRemaining == 0
                && this.unhealthyStreak >= this.thresholds.unhealthyTicksToRetract()
                && this.distancePenalty < this.thresholds.maximumDistancePenalty()) {
            this.distancePenalty++;
            this.unhealthyStreak = 0;
            this.cooldownRemaining = this.thresholds.distanceChangeCooldownTicks();
        } else if (this.cooldownRemaining == 0
                && this.healthyStreak >= this.thresholds.healthyTicksToExpand()
                && this.distancePenalty > 0) {
            this.distancePenalty--;
            this.healthyStreak = 0;
            this.cooldownRemaining = this.thresholds.distanceChangeCooldownTicks();
        }

        this.decision = new Decision(pressure, this.distancePenalty, p95, mean);
        return this.decision;
    }

    public Decision decision() {
        return this.decision;
    }

    public int permittedStarts(int configuredMaximum) {
        if (configuredMaximum <= 0) {
            return 0;
        }
        return switch (this.decision.pressure()) {
            case HEALTHY -> configuredMaximum;
            case CONSTRAINED -> Math.max(1, configuredMaximum / 2);
            case CRITICAL -> 0;
        };
    }

    private double mean() {
        if (this.sampleCount == 0) {
            return 0.0;
        }
        double total = 0.0;
        for (int index = 0; index < this.sampleCount; index++) {
            total += this.samples[index];
        }
        return total / this.sampleCount;
    }

    private double percentile95() {
        if (this.sampleCount == 0) {
            return 0.0;
        }
        double[] copy = Arrays.copyOf(this.samples, this.sampleCount);
        Arrays.sort(copy);
        int index = Math.max(0, (int) Math.ceil(copy.length * 0.95) - 1);
        return copy[index];
    }

    public record Decision(Pressure pressure, int distancePenalty, double p95Millis, double meanMillis) {
    }

    public record Thresholds(
            int sampleWindowTicks,
            double softP95Millis,
            double hardP95Millis,
            double criticalTickMillis,
            int unhealthyTicksToRetract,
            int healthyTicksToExpand,
            int distanceChangeCooldownTicks,
            int maximumDistancePenalty) {

        public Thresholds {
            if (sampleWindowTicks < 20) {
                throw new IllegalArgumentException("sampleWindowTicks must be at least 20");
            }
            if (!(softP95Millis > 0.0
                    && hardP95Millis > softP95Millis
                    && criticalTickMillis >= hardP95Millis)) {
                throw new IllegalArgumentException("tick thresholds must be ordered and positive");
            }
            if (unhealthyTicksToRetract < 1
                    || healthyTicksToExpand < 1
                    || distanceChangeCooldownTicks < 0
                    || maximumDistancePenalty < 0) {
                throw new IllegalArgumentException("hysteresis values are invalid");
            }
        }
    }
}
