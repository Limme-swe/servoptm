package io.github.limmeswe.headroom.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic chunk geometry shared by the planner and tests.
 */
public final class ChunkGeometry {

    private static final Map<Integer, List<ChunkOffset>> SEND_BOUNDARIES = new ConcurrentHashMap<>();

    private ChunkGeometry() {
    }

    /**
     * Returns the exact additional offsets admitted when Paper's send distance
     * grows from {@code distance - 1} to {@code distance}.
     *
     * <p>The shape mirrors Paper/Moonrise's square guard plus cylindrical
     * distance test. The result is immutable, deterministic, and cached.</p>
     *
     * @param distance configured send distance, at least 2
     * @return boundary offsets for that distance
     */
    public static List<ChunkOffset> sendBoundary(int distance) {
        if (distance < 2) {
            throw new IllegalArgumentException("distance must be at least 2");
        }
        return SEND_BOUNDARIES.computeIfAbsent(distance, ChunkGeometry::buildSendBoundary);
    }

    private static List<ChunkOffset> buildSendBoundary(int distance) {
        int extent = distance + 1;
        List<ChunkOffset> offsets = new ArrayList<>();
        for (int x = -extent; x <= extent; x++) {
            for (int z = -extent; z <= extent; z++) {
                if (isWithinSendDistance(x, z, distance)
                        && !isWithinSendDistance(x, z, distance - 1)) {
                    offsets.add(new ChunkOffset(x, z));
                }
            }
        }
        offsets.sort(Comparator
                .comparingLong(ChunkOffset::squaredDistance)
                .thenComparingInt(ChunkOffset::x)
                .thenComparingInt(ChunkOffset::z));
        return List.copyOf(offsets);
    }

    /**
     * Mirrors the effective Paper/Moonrise send-distance shape.
     */
    public static boolean isWithinSendDistance(int offsetX, int offsetZ, int distance) {
        if (distance < 1) {
            return false;
        }
        int absoluteX = Math.abs(offsetX);
        int absoluteZ = Math.abs(offsetZ);
        if (Math.max(absoluteX, absoluteZ) > distance + 1) {
            return false;
        }
        long adjustedX = Math.max(0, absoluteX - 2);
        long adjustedZ = Math.max(0, absoluteZ - 2);
        return adjustedX * adjustedX + adjustedZ * adjustedZ < (long) distance * distance;
    }

    public static double alignment(int offsetX, int offsetZ, double velocityX, double velocityZ) {
        double velocityLength = Math.hypot(velocityX, velocityZ);
        double offsetLength = Math.hypot(offsetX, offsetZ);
        if (velocityLength < 1.0e-9 || offsetLength < 1.0e-9) {
            return 0.0;
        }
        double value = (offsetX * velocityX + offsetZ * velocityZ) / (offsetLength * velocityLength);
        return Math.max(-1.0, Math.min(1.0, value));
    }
}
