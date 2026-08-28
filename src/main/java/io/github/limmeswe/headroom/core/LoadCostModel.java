package io.github.limmeswe.headroom.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded online model of region load cost.
 */
public final class LoadCostModel {

    private static final double ALPHA = 0.20;
    private static final double REFERENCE_MILLIS = 5.0;
    private static final int DEFAULT_MAXIMUM_REGIONS = 4_096;

    private final int maximumRegions;
    private final Map<RegionKey, Estimate> estimates;

    public LoadCostModel() {
        this(DEFAULT_MAXIMUM_REGIONS);
    }

    public LoadCostModel(int maximumRegions) {
        if (maximumRegions < 32) {
            throw new IllegalArgumentException("maximumRegions must be at least 32");
        }
        this.maximumRegions = maximumRegions;
        this.estimates = new LinkedHashMap<>(256, 0.75f, true);
    }

    public double predictedCost(ChunkKey chunk) {
        Estimate estimate = estimates.get(chunk.region());
        if (estimate == null) {
            return 1.0;
        }
        double latencyCost = Math.max(0.35, estimate.ewmaMillis / REFERENCE_MILLIS);
        double failurePenalty = 1.0 + estimate.failureScore * 2.0;
        return Math.min(20.0, latencyCost * failurePenalty);
    }

    public void recordSuccess(ChunkKey chunk, double elapsedMillis) {
        if (!Double.isFinite(elapsedMillis) || elapsedMillis < 0.0) {
            return;
        }
        Estimate estimate = estimate(chunk, elapsedMillis);
        estimate.ewmaMillis = ewma(estimate.ewmaMillis, elapsedMillis);
        estimate.failureScore *= 0.80;
    }

    public void recordFailure(ChunkKey chunk) {
        Estimate estimate = estimate(chunk, REFERENCE_MILLIS);
        estimate.failureScore = Math.min(1.0, estimate.failureScore * 0.80 + 0.20);
    }

    public int trackedRegions() {
        return estimates.size();
    }

    private Estimate estimate(ChunkKey chunk, double initialMillis) {
        Estimate estimate = estimates.computeIfAbsent(chunk.region(), ignored -> new Estimate(initialMillis));
        while (estimates.size() > maximumRegions) {
            RegionKey oldest = estimates.keySet().iterator().next();
            estimates.remove(oldest);
        }
        return estimate;
    }

    private static double ewma(double current, double sample) {
        return current + ALPHA * (sample - current);
    }

    private static final class Estimate {
        private double ewmaMillis;
        private double failureScore;

        private Estimate(double initialMillis) {
            ewmaMillis = initialMillis;
        }
    }
}
