package io.github.limmeswe.headroom.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HorizonGraphTest {

    @Test
    void coalescesSharedDemandAndPrioritizesItsUtility() {
        UUID world = UUID.randomUUID();
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        ChunkKey shared = new ChunkKey(world, 8, 8);
        ChunkKey solo = new ChunkKey(world, 9, 8);
        HorizonGraph graph = new HorizonGraph();
        graph.beginEpoch(7L);

        graph.submit(contribution(firstPlayer, shared, DemandClass.REACHABILITY, 1.0));
        graph.submit(contribution(secondPlayer, shared, DemandClass.REACHABILITY, 1.0));
        graph.submit(contribution(firstPlayer, solo, DemandClass.REACHABILITY, 1.0));

        List<ScheduledDemand> selected = graph.select(
                2,
                1_000_000_000L,
                RegionAffinity.none(),
                new FairShareLedger(4.0, 8.0),
                List.of(firstPlayer, secondPlayer)
        );

        assertFalse(selected.isEmpty());
        assertEquals(shared, selected.getFirst().chunk());
        assertEquals(Set.of(firstPlayer, secondPlayer), selected.getFirst().requesters());
        assertEquals(2, graph.nodeCount());
        assertEquals(3, graph.contributionCount());
        assertEquals(1, graph.coalescedContributionCount());
    }

    @Test
    void commitBoundaryOutranksEqualCostSpeculation() {
        UUID world = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        ChunkKey commit = new ChunkKey(world, 3, 3);
        ChunkKey speculative = new ChunkKey(world, 4, 4);
        HorizonGraph graph = new HorizonGraph();
        graph.beginEpoch(8L);
        graph.submit(contribution(player, speculative, DemandClass.SPECULATIVE, 1.0));
        graph.submit(contribution(player, commit, DemandClass.COMMIT_RING, 1.0));

        List<ScheduledDemand> selected = graph.select(
                1,
                1_000_000_000L,
                RegionAffinity.none(),
                new FairShareLedger(8.0, 8.0),
                List.of(player)
        );

        assertEquals(commit, selected.getFirst().chunk());
        assertTrue(selected.getFirst().commitCritical());
    }

    private static DemandContribution contribution(
            UUID player,
            ChunkKey chunk,
            DemandClass demandClass,
            double cost
    ) {
        return new DemandContribution(player, chunk, demandClass, 10, 2_000_000_000L, cost, 0.75);
    }
}
