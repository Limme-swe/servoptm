package dev.headroom.core;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Deficit-style fair sharing for global chunk work.
 */
public final class FairShareLedger {

    private final Map<UUID, Double> credits = new HashMap<>();
    private final double quantum;
    private final double maximumCredit;

    public FairShareLedger(double quantum, double maximumCredit) {
        if (!(quantum > 0.0)) {
            throw new IllegalArgumentException("quantum must be positive");
        }
        if (maximumCredit < quantum) {
            throw new IllegalArgumentException("maximumCredit must be at least one quantum");
        }
        this.quantum = quantum;
        this.maximumCredit = maximumCredit;
    }

    public void beginTick(Collection<UUID> activePlayers) {
        Set<UUID> active = new HashSet<>(activePlayers);
        this.credits.keySet().retainAll(active);
        for (UUID playerId : active) {
            this.credits.merge(playerId, this.quantum, (left, right) -> Math.min(this.maximumCredit, left + right));
        }
    }

    public double credit(UUID playerId) {
        return this.credits.getOrDefault(playerId, 0.0);
    }

    public UUID chargeMostEntitled(Collection<UUID> candidates, double cost) {
        UUID selected = null;
        double selectedCredit = Double.NEGATIVE_INFINITY;
        for (UUID candidate : candidates) {
            double candidateCredit = credit(candidate);
            if (selected == null
                    || candidateCredit > selectedCredit
                    || (candidateCredit == selectedCredit && candidate.compareTo(selected) < 0)) {
                selected = candidate;
                selectedCredit = candidateCredit;
            }
        }
        if (selected != null) {
            this.credits.merge(selected, -Math.max(0.1, cost), Double::sum);
        }
        return selected;
    }
}
