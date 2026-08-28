package dev.headroom.core;

import java.util.Objects;
import java.util.UUID;

/**
 * One player's need for one chunk in the next complete ring.
 */
public record ChunkDemand(
        UUID playerId,
        ChunkCoordinate coordinate,
        int targetRadius,
        int remainingInRing,
        long deadlineTick,
        double predictionPriority,
        long generation) {

    public ChunkDemand {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(coordinate, "coordinate");
        if (targetRadius < PaperViewGeometry.MIN_RADIUS || targetRadius > PaperViewGeometry.MAX_RADIUS) {
            throw new IllegalArgumentException("targetRadius is outside Paper's supported range");
        }
        if (remainingInRing < 1) {
            throw new IllegalArgumentException("remainingInRing must be positive");
        }
        if (!Double.isFinite(predictionPriority)) {
            throw new IllegalArgumentException("predictionPriority must be finite");
        }
    }
}
