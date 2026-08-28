package io.github.limmeswe.headroom.core;

import java.util.Objects;
import java.util.UUID;

/**
 * One player's contribution to a globally coalesced chunk demand.
 */
public record DemandContribution(
        UUID playerId,
        ChunkKey chunk,
        DemandClass demandClass,
        int ringDistance,
        long deadlineNanos,
        double predictedCost,
        double motionAlignment
) {

    public DemandContribution {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(demandClass, "demandClass");
        if (ringDistance < 0) {
            throw new IllegalArgumentException("ringDistance must be non-negative");
        }
        if (!Double.isFinite(predictedCost) || predictedCost <= 0.0) {
            throw new IllegalArgumentException("predictedCost must be finite and positive");
        }
        if (!Double.isFinite(motionAlignment) || motionAlignment < -1.0 || motionAlignment > 1.0) {
            throw new IllegalArgumentException("motionAlignment must be between -1 and 1");
        }
    }
}
