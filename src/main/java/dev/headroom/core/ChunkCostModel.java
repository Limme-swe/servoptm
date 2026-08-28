package dev.headroom.core;

import dev.headroom.core.ChunkCoordinate.RegionCoordinate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A bounded exponentially weighted model of observed asynchronous chunk preparation cost.
 */
public final class ChunkCostModel {

    private static final double MIN_MILLIS = 0.05;
    private static final double MAX_MILLIS = 250.0;

    private final int maximumRegions;
    private final double alpha;
    private final Map<RegionCoordinate, Double> regionMillis;
    private double globalMillis = 2.0;

    public ChunkCostModel(int maximumRegions, double alpha) {
        if (maximumRegions < 1) {
            throw new IllegalArgumentException("maximumRegions must be positive");
        }
        if (!(alpha > 0.0 && alpha <= 1.0)) {
            throw new IllegalArgumentException("alpha must be in (0, 1]");
        }
        this.maximumRegions = maximumRegions;
        this.alpha = alpha;
        this.regionMillis = new LinkedHashMap<>(128, 0.75f, true);
    }

    public synchronized double predictMillis(ChunkCoordinate coordinate) {
        return this.regionMillis.getOrDefault(coordinate.region(), this.globalMillis);
    }

    public synchronized void record(ChunkCoordinate coordinate, long elapsedNanos) {
        double measured = clamp(elapsedNanos / 1_000_000.0);
        this.globalMillis = ewma(this.globalMillis, measured);

        RegionCoordinate region = coordinate.region();
        double previous = this.regionMillis.getOrDefault(region, this.globalMillis);
        this.regionMillis.put(region, ewma(previous, measured));
        evictIfNecessary();
    }

    public synchronized int trackedRegionCount() {
        return this.regionMillis.size();
    }

    private double ewma(double previous, double measured) {
        return previous + this.alpha * (measured - previous);
    }

    private void evictIfNecessary() {
        while (this.regionMillis.size() > this.maximumRegions) {
            RegionCoordinate eldest = this.regionMillis.keySet().iterator().next();
            this.regionMillis.remove(eldest);
        }
    }

    private static double clamp(double value) {
        return Math.max(MIN_MILLIS, Math.min(MAX_MILLIS, value));
    }
}
