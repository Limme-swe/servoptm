package io.github.limmeswe.headroom.core;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable movement sample consumed by the reachability planner.
 */
public record PlayerMotion(
        UUID playerId,
        UUID worldId,
        int chunkX,
        int chunkZ,
        double velocityX,
        double velocityZ,
        double speedBlocksPerSecond,
        double turnUncertainty,
        int committedDistance,
        int maximumDistance,
        long sampledAtNanos
) {

    public PlayerMotion {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(worldId, "worldId");
        if (!Double.isFinite(velocityX) || !Double.isFinite(velocityZ)) {
            throw new IllegalArgumentException("velocity must be finite");
        }
        if (!Double.isFinite(speedBlocksPerSecond) || speedBlocksPerSecond < 0.0) {
            throw new IllegalArgumentException("speedBlocksPerSecond must be finite and non-negative");
        }
        if (!Double.isFinite(turnUncertainty) || turnUncertainty < 0.0 || turnUncertainty > 1.0) {
            throw new IllegalArgumentException("turnUncertainty must be between 0 and 1");
        }
        if (committedDistance < 2) {
            throw new IllegalArgumentException("committedDistance must be at least 2");
        }
        if (maximumDistance < committedDistance) {
            throw new IllegalArgumentException("maximumDistance must be at least committedDistance");
        }
    }
}
