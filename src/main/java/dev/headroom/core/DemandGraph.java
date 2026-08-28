package dev.headroom.core;

import dev.headroom.core.ChunkCoordinate.RegionCoordinate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Coalesces every player's ring work into one global utility queue.
 */
public final class DemandGraph {

    private final Map<ChunkCoordinate, List<ChunkDemand>> demands = new HashMap<>();

    public void add(ChunkDemand demand) {
        this.demands.computeIfAbsent(demand.coordinate(), ignored -> new ArrayList<>()).add(demand);
    }

    public int uniqueChunkCount() {
        return this.demands.size();
    }

    public void clear() {
        this.demands.clear();
    }

    public List<ScheduledDemand> schedule(
            int limit,
            long currentTick,
            ChunkCostModel costModel,
            FairShareLedger fairShareLedger,
            RegionCoordinate localityHint) {
        if (limit <= 0 || this.demands.isEmpty()) {
            return List.of();
        }

        List<ScheduledDemand> candidates = new ArrayList<>(this.demands.size());
        for (Map.Entry<ChunkCoordinate, List<ChunkDemand>> entry : this.demands.entrySet()) {
            List<ChunkDemand> contributors = List.copyOf(entry.getValue());
            Set<UUID> players = new LinkedHashSet<>();
            int minimumRemaining = Integer.MAX_VALUE;
            long earliestDeadline = Long.MAX_VALUE;
            double prediction = 0.0;
            for (ChunkDemand demand : contributors) {
                players.add(demand.playerId());
                minimumRemaining = Math.min(minimumRemaining, demand.remainingInRing());
                earliestDeadline = Math.min(earliestDeadline, demand.deadlineTick());
                prediction = Math.max(prediction, demand.predictionPriority());
            }

            long slack = Math.max(0L, earliestDeadline - currentTick);
            double completion = 1.0 / minimumRemaining;
            double urgency = 1.0 / (1.0 + slack);
            double sharing = Math.log1p(players.size());
            double fairness = 0.0;
            for (UUID playerId : players) {
                fairness = Math.max(fairness, fairShareLedger.credit(playerId));
            }
            double locality = entry.getKey().region().equals(localityHint) ? 1.0 : 0.0;
            double predictedCost = costModel.predictMillis(entry.getKey());

            double score =
                    completion * 8.0
                            + urgency * 5.0
                            + sharing * 2.5
                            + prediction * 1.5
                            + fairness * 0.35
                            + locality * 0.8
                            - Math.log1p(predictedCost) * 0.65;
            candidates.add(new ScheduledDemand(entry.getKey(), contributors, score, predictedCost));
        }

        candidates.sort(Comparator.comparingDouble(ScheduledDemand::score)
                .reversed()
                .thenComparing(scheduled -> scheduled.coordinate().worldId())
                .thenComparingInt(scheduled -> scheduled.coordinate().x())
                .thenComparingInt(scheduled -> scheduled.coordinate().z()));

        int resultSize = Math.min(limit, candidates.size());
        List<ScheduledDemand> result = new ArrayList<>(resultSize);
        for (int index = 0; index < resultSize; index++) {
            ScheduledDemand scheduled = candidates.get(index);
            Collection<UUID> contributors =
                    scheduled.contributors().stream().map(ChunkDemand::playerId).distinct().toList();
            fairShareLedger.chargeMostEntitled(contributors, Math.max(0.5, scheduled.predictedCostMillis()));
            result.add(scheduled);
        }
        return List.copyOf(result);
    }

    public record ScheduledDemand(
            ChunkCoordinate coordinate,
            List<ChunkDemand> contributors,
            double score,
            double predictedCostMillis) {
    }
}
