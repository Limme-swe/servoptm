package io.github.limmeswe.headroom.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mutable, epoch-local aggregation of all players that want the same chunk.
 */
final class AggregateDemand {

    private final ChunkKey chunk;
    private final Map<UUID, DemandContribution> contributions = new LinkedHashMap<>();

    AggregateDemand(ChunkKey chunk) {
        this.chunk = chunk;
    }

    void merge(DemandContribution incoming) {
        contributions.merge(incoming.playerId(), incoming, AggregateDemand::prefer);
    }

    private static DemandContribution prefer(DemandContribution current, DemandContribution incoming) {
        int classComparison = Integer.compare(incoming.demandClass().rank(), current.demandClass().rank());
        if (classComparison > 0) {
            return incoming;
        }
        if (classComparison < 0) {
            return current;
        }
        if (incoming.deadlineNanos() < current.deadlineNanos()) {
            return incoming;
        }
        if (incoming.motionAlignment() > current.motionAlignment()) {
            return incoming;
        }
        return current;
    }

    ScheduledDemand snapshot(long nowNanos, RegionAffinity affinity) {
        DemandClass strongest = DemandClass.SPECULATIVE;
        int nearestRing = Integer.MAX_VALUE;
        long earliestDeadline = Long.MAX_VALUE;
        double predictedCost = Double.POSITIVE_INFINITY;
        double bestAlignment = -1.0;
        double supportingUtility = 0.0;

        for (DemandContribution contribution : contributions.values()) {
            strongest = DemandClass.strongerOf(strongest, contribution.demandClass());
            nearestRing = Math.min(nearestRing, contribution.ringDistance());
            earliestDeadline = Math.min(earliestDeadline, contribution.deadlineNanos());
            predictedCost = Math.min(predictedCost, contribution.predictedCost());
            bestAlignment = Math.max(bestAlignment, contribution.motionAlignment());
            supportingUtility += contribution.demandClass().baseUtility() * 0.30;
        }

        Set<UUID> requesters = Set.copyOf(contributions.keySet());
        double sharedDemandBonus = Math.log1p(requesters.size()) * 4.5;
        double deadlinePressure = deadlinePressure(nowNanos, earliestDeadline);
        double ringCompletionBonus = strongest == DemandClass.COMMIT_RING
                ? 12.0 / Math.max(1, nearestRing)
                : 0.0;
        double alignmentBonus = Math.max(0.0, bestAlignment) * 3.0;
        double localityBonus = clamp(affinity.bonusFor(chunk.region()), 0.0, 3.0);

        double utility = strongest.baseUtility()
                + supportingUtility
                + sharedDemandBonus
                + deadlinePressure
                + ringCompletionBonus
                + alignmentBonus
                + localityBonus;
        double score = utility / Math.max(0.25, predictedCost);

        return new ScheduledDemand(
                chunk,
                requesters,
                strongest,
                nearestRing == Integer.MAX_VALUE ? 0 : nearestRing,
                earliestDeadline,
                predictedCost,
                score
        );
    }

    private static double deadlinePressure(long nowNanos, long deadlineNanos) {
        if (deadlineNanos == Long.MAX_VALUE) {
            return 0.0;
        }
        long slack = Math.max(50_000_000L, deadlineNanos - nowNanos);
        return clamp(2_000_000_000.0 / slack, 0.0, 14.0);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
