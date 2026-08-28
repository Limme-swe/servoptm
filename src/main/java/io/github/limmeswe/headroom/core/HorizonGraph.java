package io.github.limmeswe.headroom.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Global chunk-demand graph for one scheduler epoch.
 *
 * <p>Independent player requests are collapsed by {@link ChunkKey}, scored by
 * visible utility per predicted cost, and filtered through a deficit ledger.</p>
 */
public final class HorizonGraph {

    private static final Comparator<ScheduledDemand> ORDER = Comparator
            .comparingDouble(ScheduledDemand::score).reversed()
            .thenComparing((ScheduledDemand value) -> value.demandClass().rank(), Comparator.reverseOrder())
            .thenComparingLong(ScheduledDemand::deadlineNanos)
            .thenComparing(ScheduledDemand::chunk);

    private final Map<ChunkKey, AggregateDemand> nodes = new HashMap<>();
    private long epoch;
    private long contributionCount;

    public void beginEpoch(long newEpoch) {
        epoch = newEpoch;
        contributionCount = 0L;
        nodes.clear();
    }

    public void submit(DemandContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        nodes.computeIfAbsent(contribution.chunk(), AggregateDemand::new).merge(contribution);
        contributionCount++;
    }

    public List<ScheduledDemand> select(
            int limit,
            long nowNanos,
            RegionAffinity affinity,
            FairShareLedger fairness,
            Collection<UUID> activePlayers
    ) {
        if (limit <= 0 || nodes.isEmpty()) {
            return List.of();
        }
        Objects.requireNonNull(affinity, "affinity");
        Objects.requireNonNull(fairness, "fairness");
        Objects.requireNonNull(activePlayers, "activePlayers");

        fairness.beginCycle(activePlayers);
        List<ScheduledDemand> candidates = new ArrayList<>(nodes.size());
        for (AggregateDemand node : nodes.values()) {
            candidates.add(node.snapshot(nowNanos, affinity));
        }
        candidates.sort(ORDER);

        List<ScheduledDemand> selected = new ArrayList<>(Math.min(limit, candidates.size()));
        for (ScheduledDemand candidate : candidates) {
            if (fairness.tryCharge(candidate.requesters(), fairnessCharge(candidate.predictedCost()))) {
                selected.add(candidate);
                if (selected.size() >= limit) {
                    break;
                }
            }
        }
        return List.copyOf(selected);
    }

    private static double fairnessCharge(double predictedCost) {
        return Math.min(4.0, 0.5 + Math.log1p(Math.max(0.0, predictedCost)));
    }

    public Set<ChunkKey> chunkKeys() {
        return Set.copyOf(nodes.keySet());
    }

    public long epoch() {
        return epoch;
    }

    public int nodeCount() {
        return nodes.size();
    }

    public long contributionCount() {
        return contributionCount;
    }

    public long coalescedContributionCount() {
        return Math.max(0L, contributionCount - nodes.size());
    }
}
