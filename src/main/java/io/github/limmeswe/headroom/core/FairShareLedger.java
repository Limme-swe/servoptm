package io.github.limmeswe.headroom.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Deficit-based fairness ledger for global chunk scheduling.
 */
public final class FairShareLedger {

    private final double quantum;
    private final double maximumCredit;
    private final Map<UUID, Double> credits = new HashMap<>();

    public FairShareLedger(double quantum, double maximumCredit) {
        if (!Double.isFinite(quantum) || quantum <= 0.0) {
            throw new IllegalArgumentException("quantum must be finite and positive");
        }
        if (!Double.isFinite(maximumCredit) || maximumCredit < quantum) {
            throw new IllegalArgumentException("maximumCredit must be finite and at least quantum");
        }
        this.quantum = quantum;
        this.maximumCredit = maximumCredit;
    }

    public void beginCycle(Collection<UUID> activePlayers) {
        Set<UUID> active = new HashSet<>(activePlayers);
        credits.keySet().retainAll(active);
        for (UUID playerId : active) {
            credits.merge(playerId, quantum, (current, added) -> Math.min(maximumCredit, current + added));
        }
    }

    public boolean tryCharge(Set<UUID> requesters, double cost) {
        if (requesters.isEmpty()) {
            return false;
        }
        double normalizedCost = Math.max(0.25, cost);
        List<UUID> ordered = new ArrayList<>(requesters);
        ordered.sort(UUID::compareTo);

        double available = 0.0;
        for (UUID requester : ordered) {
            available += Math.max(0.0, credits.getOrDefault(requester, 0.0));
        }
        if (available + 1.0e-9 < normalizedCost) {
            return false;
        }

        double remaining = normalizedCost;
        for (UUID requester : ordered) {
            if (remaining <= 1.0e-9) {
                break;
            }
            double current = Math.max(0.0, credits.getOrDefault(requester, 0.0));
            double proportionalShare = normalizedCost * (current / available);
            double charge = Math.min(current, Math.min(remaining, proportionalShare));
            credits.put(requester, current - charge);
            remaining -= charge;
        }

        if (remaining > 1.0e-9) {
            for (UUID requester : ordered) {
                double current = Math.max(0.0, credits.getOrDefault(requester, 0.0));
                double charge = Math.min(current, remaining);
                credits.put(requester, current - charge);
                remaining -= charge;
                if (remaining <= 1.0e-9) {
                    break;
                }
            }
        }
        return remaining <= 1.0e-6;
    }

    public double credit(UUID playerId) {
        return credits.getOrDefault(playerId, 0.0);
    }
}
