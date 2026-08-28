package io.github.limmeswe.headroom.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReachabilityPlannerTest {

    private static final ReachabilityPlanner.Config CONFIG = new ReachabilityPlanner.Config(
            4, 64, 1.5, 0.15, 3.0, 0.35, 6.0);

    @Test
    void guaranteedBoundaryIsCompleteAndNeverDirectional() {
        PlayerMotion motion = new PlayerMotion(
                UUID.randomUUID(), UUID.randomUUID(), 100, -20,
                1.0, 0.0, 30.0, 0.0, 8, 16, 1_000_000_000L);

        ReachabilityPlanner.Plan plan = new ReachabilityPlanner(CONFIG).plan(motion, ignored -> 1.0);
        assertEquals(ChunkGeometry.sendBoundary(9).size(), plan.commitRing().size());
        assertEquals(plan.commitRing().size(), new HashSet<>(plan.commitRing()).size());
        assertTrue(plan.commitRing().stream()
                .allMatch(contribution -> contribution.demandClass() == DemandClass.COMMIT_RING));
        assertTrue(plan.commitRing().stream().anyMatch(contribution -> contribution.motionAlignment() < 0.0));
    }

    @Test
    void stationaryPlayersDoNotCreateSpeculativeWork() {
        PlayerMotion motion = new PlayerMotion(
                UUID.randomUUID(), UUID.randomUUID(), 0, 0,
                0.0, 0.0, 0.0, 0.0, 8, 16, 1_000_000_000L);

        ReachabilityPlanner.Plan plan = new ReachabilityPlanner(CONFIG).plan(motion, ignored -> 1.0);
        assertFalse(plan.commitRing().isEmpty());
        assertTrue(plan.opportunistic().isEmpty());
    }

    @Test
    void turnUncertaintyWidensOnlyTheOpportunisticEnvelope() {
        UUID player = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        PlayerMotion stable = new PlayerMotion(
                player, world, 0, 0, 1.0, 0.0, 25.0, 0.0, 8, 16, 1_000_000_000L);
        PlayerMotion turning = new PlayerMotion(
                player, world, 0, 0, 1.0, 0.0, 25.0, 1.0, 8, 16, 1_000_000_000L);
        ReachabilityPlanner planner = new ReachabilityPlanner(CONFIG);

        ReachabilityPlanner.Plan stablePlan = planner.plan(stable, ignored -> 1.0);
        ReachabilityPlanner.Plan turningPlan = planner.plan(turning, ignored -> 1.0);
        assertEquals(stablePlan.commitRing(), turningPlan.commitRing());
        assertTrue(turningPlan.opportunistic().size() >= stablePlan.opportunistic().size());
    }
}
