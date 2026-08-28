import io.github.limmeswe.headroom.core.AdaptiveBudgetController;
import io.github.limmeswe.headroom.core.ChunkGeometry;
import io.github.limmeswe.headroom.core.ChunkKey;
import io.github.limmeswe.headroom.core.DemandClass;
import io.github.limmeswe.headroom.core.DemandContribution;
import io.github.limmeswe.headroom.core.FairShareLedger;
import io.github.limmeswe.headroom.core.HorizonGraph;
import io.github.limmeswe.headroom.core.PlayerMotion;
import io.github.limmeswe.headroom.core.ReachabilityPlanner;
import io.github.limmeswe.headroom.core.RegionAffinity;
import io.github.limmeswe.headroom.core.RingBarrier;
import io.github.limmeswe.headroom.core.ScheduledDemand;
import io.github.limmeswe.headroom.core.TokenBucket;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class CoreSelfTest {
    private CoreSelfTest() {
    }

    public static void main(String[] args) {
        geometryIsComplete();
        sharedDemandWins();
        commitRingIsNeverDirectional();
        ringBarrierRequiresEveryChunk();
        controllerHasHysteresis();
        tokenBucketRefills();
        System.out.println("Headroom core self-test: PASS");
    }

    private static void geometryIsComplete() {
        for (int distance = 2; distance <= 32; distance++) {
            List<?> boundary = ChunkGeometry.sendBoundary(distance);
            check(!boundary.isEmpty(), "boundary not empty " + distance);
            check(new HashSet<>(boundary).size() == boundary.size(), "boundary uniqueness " + distance);
        }
    }

    private static void sharedDemandWins() {
        UUID world = UUID.randomUUID();
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();
        ChunkKey shared = new ChunkKey(world, 4, 4);
        ChunkKey solo = new ChunkKey(world, 5, 5);
        HorizonGraph graph = new HorizonGraph();
        graph.beginEpoch(1);
        graph.submit(new DemandContribution(playerA, shared, DemandClass.REACHABILITY, 10,
                2_000_000_000L, 1.0, 0.8));
        graph.submit(new DemandContribution(playerB, shared, DemandClass.REACHABILITY, 10,
                2_000_000_000L, 1.0, 0.8));
        graph.submit(new DemandContribution(playerA, solo, DemandClass.REACHABILITY, 10,
                2_000_000_000L, 1.0, 0.8));
        FairShareLedger fairness = new FairShareLedger(4.0, 8.0);
        List<ScheduledDemand> selected = graph.select(
                2, 1_000_000_000L, RegionAffinity.none(), fairness, List.of(playerA, playerB));
        check(!selected.isEmpty() && selected.getFirst().chunk().equals(shared), "shared demand priority");
        check(graph.coalescedContributionCount() == 1L, "coalescing count");
    }

    private static void commitRingIsNeverDirectional() {
        UUID player = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        ReachabilityPlanner planner = new ReachabilityPlanner(new ReachabilityPlanner.Config(
                4, 64, 1.0, 0.15, 3.0, 0.35, 6.0));
        PlayerMotion motion = new PlayerMotion(player, world, 100, -20, 1.0, 0.0,
                20.0, 0.0, 8, 16, 1_000_000_000L);
        ReachabilityPlanner.Plan plan = planner.plan(motion, ignored -> 1.0);
        check(plan.commitRing().size() == ChunkGeometry.sendBoundary(9).size(), "complete boundary");
        check(plan.commitRing().stream().allMatch(value -> value.demandClass() == DemandClass.COMMIT_RING),
                "commit class");
        check(plan.commitRing().stream().anyMatch(value -> value.motionAlignment() < 0.0),
                "backward chunks retained in guaranteed boundary");
    }

    private static void ringBarrierRequiresEveryChunk() {
        UUID world = UUID.randomUUID();
        Set<ChunkKey> required = Set.of(
                new ChunkKey(world, 1, 1),
                new ChunkKey(world, 1, 2)
        );
        RingBarrier barrier = new RingBarrier(world, 0, 0, 2, 0L, required);
        barrier.markReady(new ChunkKey(world, 1, 1));
        check(!barrier.isComplete(), "barrier not early");
        barrier.markReady(new ChunkKey(world, 1, 2));
        check(barrier.isComplete(), "barrier complete");
    }

    private static void controllerHasHysteresis() {
        AdaptiveBudgetController controller = new AdaptiveBudgetController(
                new AdaptiveBudgetController.Config(80, 40, 38.0, 48.0, 55.0, 3, 2));
        for (int index = 0; index < 80; index++) {
            controller.observeTick(20.0);
        }
        check(controller.evaluate().state() == AdaptiveBudgetController.State.RECOVERING,
                "first healthy recovery window");
        check(controller.evaluate().state() == AdaptiveBudgetController.State.RECOVERING,
                "second healthy recovery window");
        check(controller.evaluate().state() == AdaptiveBudgetController.State.HEALTHY,
                "third healthy recovery window");
        for (int index = 0; index < 80; index++) {
            controller.observeTick(60.0);
        }
        check(controller.evaluate().state() == AdaptiveBudgetController.State.CONSTRAINED,
                "critical requires hysteresis");
        check(controller.evaluate().state() == AdaptiveBudgetController.State.CRITICAL,
                "critical after consecutive windows");
    }

    private static void tokenBucketRefills() {
        TokenBucket bucket = new TokenBucket(10.0, 10.0, 1_000_000_000L);
        for (int index = 0; index < 10; index++) {
            check(bucket.tryConsume(1.0, 1_000_000_000L), "initial token " + index);
        }
        check(!bucket.tryConsume(1.0, 1_000_000_000L), "empty bucket");
        check(bucket.tryConsume(1.0, 1_100_000_000L), "refilled token");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
