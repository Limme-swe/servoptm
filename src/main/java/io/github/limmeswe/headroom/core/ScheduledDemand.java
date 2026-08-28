package io.github.limmeswe.headroom.core;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable scheduler output for one coalesced chunk load.
 */
public record ScheduledDemand(
        ChunkKey chunk,
        Set<UUID> requesters,
        DemandClass demandClass,
        int nearestRing,
        long deadlineNanos,
        double predictedCost,
        double score
) {

    public ScheduledDemand {
        Objects.requireNonNull(chunk, "chunk");
        requesters = Set.copyOf(requesters);
        Objects.requireNonNull(demandClass, "demandClass");
    }

    public boolean commitCritical() {
        return demandClass == DemandClass.COMMIT_RING;
    }
}
