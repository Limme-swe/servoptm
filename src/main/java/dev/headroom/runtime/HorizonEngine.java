package dev.headroom.runtime;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import dev.headroom.config.HeadroomConfig;
import dev.headroom.core.ChunkCoordinate;
import dev.headroom.core.ChunkCostModel;
import dev.headroom.core.ChunkDemand;
import dev.headroom.core.ChunkOffset;
import dev.headroom.core.CompletenessBarrier;
import dev.headroom.core.DemandGraph;
import dev.headroom.core.FairShareLedger;
import dev.headroom.core.ReachabilityPredictor;
import dev.headroom.core.TailLatencyController;
import dev.headroom.metrics.HeadroomMetrics;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Quality-constrained global horizon scheduler.
 */
public final class HorizonEngine implements AutoCloseable {

    private final Plugin plugin;
    private final HeadroomConfig config;
    private final ClientClassifier clientClassifier;
    private final WarmLeaseRegistry leases;
    private final ChunkCostModel costModel = new ChunkCostModel(2048, 0.20);
    private final HeadroomMetrics metrics = new HeadroomMetrics();
    private final TailLatencyController latencyController;
    private final FairShareLedger fairShareLedger;
    private final HorizonBackend backend;
    private final DemandGraph demandGraph = new DemandGraph();
    private final Map<UUID, PlayerHorizon> players = new HashMap<>();

    private boolean running;
    private boolean paused;
    private long currentTick;
    private ChunkCoordinate.RegionCoordinate localityHint;

    public HorizonEngine(Plugin plugin, HeadroomConfig config) {
        this.plugin = plugin;
        this.config = config;
        Server server = plugin.getServer();
        this.clientClassifier = new ClientClassifier(
                server.getPluginManager(),
                plugin.getLogger(),
                config.geyser().detectBedrockPlayers(),
                config.geyser().logDetectionFailures());
        this.leases = new WarmLeaseRegistry(
                plugin,
                config.warmLeases().maximumChunks(),
                config.warmLeases().timeoutTicks());
        this.latencyController = new TailLatencyController(config.loadControl().thresholds());
        this.fairShareLedger =
                new FairShareLedger(config.scheduler().playerQuantum(), config.scheduler().playerQuantum() * 20.0);
        ChunkLoadCoordinator coordinator = new ChunkLoadCoordinator(
                plugin,
                this.leases,
                this.costModel,
                this.metrics,
                config.scheduler().maximumInFlightLoads(),
                () -> this.currentTick);
        this.backend = new PaperNativeBackend(coordinator);
    }

    public void start() {
        requirePrimaryThread();
        if (this.running) {
            return;
        }
        this.running = true;
        this.paused = !this.config.enabled();
        if (!this.paused) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                addPlayer(player);
            }
        }
        this.plugin.getLogger().info(
                "Headroom started with backend " + this.backend.name()
                        + "; standard packet path=" + this.backend.preservesStandardPacketPath());
    }

    public void addPlayer(Player player) {
        requirePrimaryThread();
        if (!this.running || this.paused || !player.isOnline()) {
            return;
        }
        this.players.computeIfAbsent(
                player.getUniqueId(),
                ignored -> new PlayerHorizon(
                        player,
                        this.plugin.getServer(),
                        this.clientClassifier.isBedrock(player.getUniqueId())));
    }

    public void removePlayer(Player player) {
        requirePrimaryThread();
        PlayerHorizon state = this.players.remove(player.getUniqueId());
        if (state != null) {
            LeaseOwner owner = state.plan() == null ? null : state.plan().owner();
            state.restore(player);
            if (owner != null) {
                this.leases.releaseOwner(owner);
            }
            this.leases.releasePlayer(player.getUniqueId());
        }
    }

    public void resetPlayer(Player player) {
        requirePrimaryThread();
        PlayerHorizon previous = this.players.remove(player.getUniqueId());
        if (previous != null) {
            LeaseOwner owner = previous.plan() == null ? null : previous.plan().owner();
            previous.restore(player);
            if (owner != null) {
                this.leases.releaseOwner(owner);
            }
            this.leases.releasePlayer(player.getUniqueId());
        }
        addPlayer(player);
    }

    public void updateClientDistance(Player player, int distance) {
        requirePrimaryThread();
        PlayerHorizon state = this.players.get(player.getUniqueId());
        if (state != null) {
            state.setClientRequestedDistance(distance);
        }
    }

    public void refreshClientDetection() {
        requirePrimaryThread();
        this.clientClassifier.refresh();
        for (PlayerHorizon state : this.players.values()) {
            state.setBedrock(this.clientClassifier.isBedrock(state.playerId()));
        }
    }

    public void confirmChunkDelivery(Player player, Chunk chunk) {
        requirePrimaryThread();
        PlayerHorizon state = this.players.get(player.getUniqueId());
        if (state == null || !chunk.getWorld().getUID().equals(state.worldId())) {
            return;
        }
        ChunkCoordinate coordinate =
                new ChunkCoordinate(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        state.confirmDelivery(coordinate);
    }

    public void observeChunkUnload(Player player, Chunk chunk) {
        requirePrimaryThread();
        PlayerHorizon state = this.players.get(player.getUniqueId());
        if (state == null || state.plan() == null) {
            return;
        }
        PlayerHorizon.RingPlan plan = state.plan();
        if (plan.barrier().phase() != CompletenessBarrier.Phase.DELIVERING) {
            return;
        }
        ChunkCoordinate coordinate =
                new ChunkCoordinate(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        if (plan.barrier().expected().contains(coordinate)) {
            rollback(state, player, "Paper unloaded a boundary chunk before confirmation completed");
        }
    }

    public void tick(ServerTickEndEvent event) {
        requirePrimaryThread();
        if (!this.running) {
            return;
        }

        this.currentTick = event.getTickNumber();
        TailLatencyController.Decision decision =
                this.latencyController.sample(event.getTickDuration());

        for (LeaseOwner expiredOwner : this.leases.expire(this.currentTick)) {
            PlayerHorizon state = this.players.get(expiredOwner.playerId());
            Player player = Bukkit.getPlayer(expiredOwner.playerId());
            if (state != null
                    && player != null
                    && state.plan() != null
                    && state.plan().owner().equals(expiredOwner)) {
                rollback(state, player, "warm lease expired");
            }
        }

        if (this.currentTick % 1200L == 0L) {
            refreshClientDetection();
        }
        if (this.paused) {
            return;
        }

        synchronizeOnlinePlayers();
        Set<UUID> activePlayerIds = new HashSet<>(this.players.keySet());
        this.fairShareLedger.beginTick(activePlayerIds);
        this.demandGraph.clear();

        long schedulingStart = System.nanoTime();
        long configuredBudgetNanos = this.config.scheduler().mainThreadBudgetMicros() * 1_000L;
        long availableTickNanos = Math.max(0L, event.getTimeRemaining());
        long schedulingDeadline =
                schedulingStart + Math.min(configuredBudgetNanos, availableTickNanos);
        List<UUID> stalePlayers = new ArrayList<>();

        for (PlayerHorizon state : this.players.values()) {
            Player player = Bukkit.getPlayer(state.playerId());
            if (player == null || !player.isOnline()) {
                stalePlayers.add(state.playerId());
                continue;
            }

            if (!this.config.isWorldEnabled(player.getWorld())) {
                LeaseOwner owner = state.plan() == null ? null : state.plan().owner();
                state.retract(player, state.baselineEffectiveDistance());
                if (owner != null) {
                    this.leases.releaseOwner(owner);
                }
                continue;
            }

            LeaseOwner ownerBeforePosition = state.plan() == null ? null : state.plan().owner();
            if (state.updatePosition(player) && ownerBeforePosition != null) {
                this.leases.releaseOwner(ownerBeforePosition);
            }
            LeaseOwner ownerBeforeOverride = state.plan() == null ? null : state.plan().owner();
            if (state.adoptExternalOverride(player, this.plugin.getServer())
                    && ownerBeforeOverride != null) {
                this.leases.releaseOwner(ownerBeforeOverride);
            }

            int configuredCeiling = this.config.ceilingFor(player.getWorld(), state.bedrock());
            int desiredDistance =
                    state.desiredDistance(configuredCeiling, decision.distancePenalty());
            if (state.committedDistance() > desiredDistance || state.plan() != null
                    && state.plan().radius() > desiredDistance) {
                LeaseOwner owner = state.plan() == null ? null : state.plan().owner();
                state.retract(player, desiredDistance);
                if (owner != null) {
                    this.leases.releaseOwner(owner);
                }
            }

            PlayerHorizon.RingPlan plan = state.plan();
            if (plan != null) {
                this.leases.renewOwner(plan.owner(), this.currentTick);
                if (plan.barrier().isComplete()) {
                    if (state.completePlan()) {
                        this.metrics.ringCompleted();
                        this.leases.releaseOwner(plan.owner());
                    }
                    continue;
                }

                if (plan.barrier().phase() == CompletenessBarrier.Phase.DELIVERING) {
                    if (this.currentTick - plan.promotedTick()
                            > this.config.horizon().deliveryTimeoutTicks()) {
                        rollback(state, player, "delivery confirmation timed out");
                    }
                    continue;
                }

                if (plan.barrier().isPreparationComplete()) {
                    if (decision.pressure() != TailLatencyController.Pressure.CRITICAL
                            && event.getTimeRemaining() > 0L) {
                        state.promote(player, this.currentTick);
                        this.metrics.ringPromoted();
                    }
                    continue;
                }

                addMissingDemands(state, plan);
                continue;
            }

            if (state.committedDistance() < desiredDistance && state.retryReady(this.currentTick)) {
                PlayerHorizon.RingPlan created =
                        state.startPlan(player.getWorld(), state.committedDistance() + 1, this.currentTick);
                addMissingDemands(state, created);
            }
        }

        for (UUID stalePlayer : stalePlayers) {
            this.players.remove(stalePlayer);
            this.leases.releasePlayer(stalePlayer);
        }

        if (System.nanoTime() >= schedulingDeadline || event.getTimeRemaining() <= 0L) {
            return;
        }

        int starts = this.latencyController.permittedStarts(
                this.config.scheduler().maximumLoadStartsPerTick());
        starts = Math.min(
                starts,
                Math.max(0, this.config.scheduler().maximumInFlightLoads() - this.backend.inFlightCount()));

        List<DemandGraph.ScheduledDemand> scheduled = this.demandGraph.schedule(
                starts,
                this.currentTick,
                this.costModel,
                this.fairShareLedger,
                this.localityHint);
        for (DemandGraph.ScheduledDemand work : scheduled) {
            if (System.nanoTime() >= schedulingDeadline) {
                break;
            }
            this.localityHint = work.coordinate().region();
            dispatch(work);
        }
    }

    public void pause() {
        requirePrimaryThread();
        if (this.paused) {
            return;
        }
        this.paused = true;
        for (Map.Entry<UUID, PlayerHorizon> entry : this.players.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            PlayerHorizon state = entry.getValue();
            LeaseOwner owner = state.plan() == null ? null : state.plan().owner();
            if (player != null) {
                state.restore(player);
            }
            if (owner != null) {
                this.leases.releaseOwner(owner);
            }
            this.leases.releasePlayer(entry.getKey());
        }
        this.players.clear();
    }

    public void resume() {
        requirePrimaryThread();
        if (!this.paused) {
            return;
        }
        this.paused = false;
        for (Player player : Bukkit.getOnlinePlayers()) {
            addPlayer(player);
        }
    }

    public boolean isPaused() {
        return this.paused;
    }

    public Status status() {
        TailLatencyController.Decision decision = this.latencyController.decision();
        Collection<PlayerStatus> playerStatuses = this.players.values().stream()
                .map(state -> new PlayerStatus(
                        state.playerId(),
                        state.committedDistance(),
                        state.plan() == null ? null : state.plan().radius(),
                        state.bedrock()))
                .toList();
        return new Status(
                this.running,
                this.paused,
                this.backend.name(),
                decision,
                this.players.size(),
                this.leases.leaseCount(),
                this.backend.inFlightCount(),
                this.demandGraph.uniqueChunkCount(),
                this.metrics.snapshot(),
                playerStatuses);
    }

    @Override
    public void close() {
        stop();
    }

    public void stop() {
        requirePrimaryThread();
        if (!this.running) {
            return;
        }
        this.running = false;
        for (Map.Entry<UUID, PlayerHorizon> entry : this.players.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                entry.getValue().restore(player);
            }
        }
        this.players.clear();
        this.backend.close();
        this.leases.close();
    }

    private void addMissingDemands(PlayerHorizon state, PlayerHorizon.RingPlan plan) {
        int remaining = plan.barrier().missingPreparation().size();
        for (ChunkCoordinate coordinate : plan.barrier().missingPreparation()) {
            if (plan.isRequested(coordinate)) {
                continue;
            }
            ChunkOffset offset =
                    new ChunkOffset(coordinate.x() - state.centerX(), coordinate.z() - state.centerZ());
            double prediction = ReachabilityPredictor.priority(offset, state.motion());
            long deadline = this.currentTick + Math.max(5L, 40L - Math.round(prediction * 12.0));
            this.demandGraph.add(new ChunkDemand(
                    state.playerId(),
                    coordinate,
                    plan.radius(),
                    remaining,
                    deadline,
                    prediction,
                    plan.generation()));
        }
    }

    private void dispatch(DemandGraph.ScheduledDemand work) {
        Map<UUID, ChunkDemand> contributors = new HashMap<>();
        for (ChunkDemand demand : work.contributors()) {
            contributors.putIfAbsent(demand.playerId(), demand);
        }

        for (ChunkDemand demand : contributors.values()) {
            PlayerHorizon state = this.players.get(demand.playerId());
            if (state == null) {
                continue;
            }
            PlayerHorizon.RingPlan plan = state.plan();
            if (plan == null
                    || plan.generation() != demand.generation()
                    || plan.radius() != demand.targetRadius()
                    || !plan.markRequested(demand.coordinate())) {
                continue;
            }

            CompletableFuture<ChunkLoadCoordinator.Result> future = this.backend.prepare(
                    demand.coordinate(),
                    plan.owner(),
                    this.config.horizon().generateNewChunks());
            future.whenComplete((result, throwable) -> {
                requirePrimaryThread();
                handlePreparationResult(demand, result, throwable);
            });
        }
    }

    private void handlePreparationResult(
            ChunkDemand demand,
            ChunkLoadCoordinator.Result result,
            Throwable throwable) {
        PlayerHorizon state = this.players.get(demand.playerId());
        Player player = Bukkit.getPlayer(demand.playerId());
        LeaseOwner demandOwner =
                new LeaseOwner(demand.playerId(), demand.generation(), demand.targetRadius());
        if (state == null || player == null) {
            this.leases.releaseOwner(demandOwner);
            return;
        }

        PlayerHorizon.RingPlan plan = state.plan();
        if (plan == null
                || plan.generation() != demand.generation()
                || plan.radius() != demand.targetRadius()) {
            this.leases.releaseOwner(demandOwner);
            return;
        }
        plan.clearRequested(demand.coordinate());

        if (throwable != null || result == null) {
            this.plugin.getLogger().log(Level.FINE, "Chunk preparation failed", throwable);
            state.blockRetry(this.currentTick + this.config.horizon().retryDelayTicks());
            this.leases.releaseOwner(plan.owner());
            return;
        }

        switch (result.status()) {
            case READY -> plan.barrier().markPrepared(demand.coordinate());
            case UNGENERATED -> {
                state.blockRetry(this.currentTick + this.config.horizon().retryDelayTicks());
                this.leases.releaseOwner(plan.owner());
            }
            case CAPACITY -> {
                // Clear the requested marker and allow the fair scheduler to retry next tick.
            }
            case FAILED -> {
                if (result.failure() != null) {
                    this.plugin.getLogger().log(Level.FINE, "Paper chunk request failed", result.failure());
                }
                state.blockRetry(this.currentTick + this.config.horizon().retryDelayTicks());
                this.leases.releaseOwner(plan.owner());
            }
            case STOPPED -> {
                // Engine shutdown invalidates every outstanding callback.
            }
        }
    }

    private void rollback(PlayerHorizon state, Player player, String reason) {
        this.plugin.getLogger().fine(
                () -> "Rolling back ring for " + player.getName() + ": " + reason);
        LeaseOwner owner = state.plan() == null ? null : state.plan().owner();
        state.rollback(
                player,
                this.currentTick + this.config.horizon().retryDelayTicks());
        if (owner != null) {
            this.leases.releaseOwner(owner);
        }
        this.metrics.ringRolledBack();
    }

    private void synchronizeOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            addPlayer(player);
        }
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("HorizonEngine must run on the server thread");
        }
    }

    public record Status(
            boolean running,
            boolean paused,
            String backend,
            TailLatencyController.Decision latency,
            int managedPlayers,
            int warmLeases,
            int inFlightLoads,
            int queuedUniqueChunks,
            HeadroomMetrics.Snapshot metrics,
            Collection<PlayerStatus> players) {
    }

    public record PlayerStatus(
            UUID playerId,
            int committedDistance,
            Integer pendingDistance,
            boolean bedrock) {
    }
}
