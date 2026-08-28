package dev.headroom.runtime;

import com.destroystokyo.paper.ClientOption;
import dev.headroom.core.ChunkCoordinate;
import dev.headroom.core.ChunkOffset;
import dev.headroom.core.CompletenessBarrier;
import dev.headroom.core.PaperViewGeometry;
import dev.headroom.core.ReachabilityPredictor;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Main-thread state machine for one player's committed and pending horizons.
 */
final class PlayerHorizon {

    private final UUID playerId;

    private int baselineSendSetting;
    private int baselineEffectiveDistance;
    private int lastAppliedSendSetting;
    private int committedDistance;
    private int clientRequestedDistance;
    private boolean bedrock;

    private UUID worldId;
    private int centerX;
    private int centerZ;
    private double lastX;
    private double lastZ;
    private ReachabilityPredictor.Motion motion = ReachabilityPredictor.Motion.stationary();
    private long generation;
    private long nextRetryTick;
    private RingPlan plan;

    PlayerHorizon(Player player, Server server, boolean bedrock) {
        this.playerId = player.getUniqueId();
        this.bedrock = bedrock;
        this.baselineSendSetting = player.getSendViewDistance();
        this.lastAppliedSendSetting = this.baselineSendSetting;
        this.baselineEffectiveDistance = resolveEffective(this.baselineSendSetting, server.getViewDistance());
        this.committedDistance = this.baselineEffectiveDistance;
        this.clientRequestedDistance = readClientDistance(player, this.baselineEffectiveDistance);

        Location location = player.getLocation();
        this.worldId = location.getWorld().getUID();
        this.centerX = location.getBlockX() >> 4;
        this.centerZ = location.getBlockZ() >> 4;
        this.lastX = location.getX();
        this.lastZ = location.getZ();
    }

    UUID playerId() {
        return this.playerId;
    }

    UUID worldId() {
        return this.worldId;
    }

    int centerX() {
        return this.centerX;
    }

    int centerZ() {
        return this.centerZ;
    }

    int committedDistance() {
        return this.committedDistance;
    }

    int baselineEffectiveDistance() {
        return this.baselineEffectiveDistance;
    }

    int clientRequestedDistance() {
        return this.clientRequestedDistance;
    }

    boolean bedrock() {
        return this.bedrock;
    }

    long generation() {
        return this.generation;
    }

    ReachabilityPredictor.Motion motion() {
        return this.motion;
    }

    RingPlan plan() {
        return this.plan;
    }

    boolean retryReady(long currentTick) {
        return currentTick >= this.nextRetryTick;
    }

    void setClientRequestedDistance(int distance) {
        this.clientRequestedDistance =
                Math.max(PaperViewGeometry.MIN_RADIUS, Math.min(PaperViewGeometry.MAX_RADIUS, distance));
    }

    void setBedrock(boolean bedrock) {
        this.bedrock = bedrock;
    }

    boolean updatePosition(Player player) {
        Location location = player.getLocation();
        World world = location.getWorld();
        int nextCenterX = location.getBlockX() >> 4;
        int nextCenterZ = location.getBlockZ() >> 4;

        double deltaX = location.getX() - this.lastX;
        double deltaZ = location.getZ() - this.lastZ;
        this.motion = new ReachabilityPredictor.Motion(deltaX, deltaZ, Math.hypot(deltaX, deltaZ));
        this.lastX = location.getX();
        this.lastZ = location.getZ();

        boolean changed = !world.getUID().equals(this.worldId)
                || nextCenterX != this.centerX
                || nextCenterZ != this.centerZ;
        if (changed) {
            this.worldId = world.getUID();
            this.centerX = nextCenterX;
            this.centerZ = nextCenterZ;
            this.generation++;
            cancelPlan();
        }
        return changed;
    }

    boolean adoptExternalOverride(Player player, Server server) {
        int current = player.getSendViewDistance();
        if (current == this.lastAppliedSendSetting) {
            return false;
        }

        this.baselineSendSetting = current;
        this.lastAppliedSendSetting = current;
        this.baselineEffectiveDistance = resolveEffective(current, server.getViewDistance());
        this.committedDistance = this.baselineEffectiveDistance;
        this.generation++;
        cancelPlan();
        return true;
    }

    int desiredDistance(int configuredCeiling, int adaptivePenalty) {
        int adaptiveCeiling = Math.max(
                PaperViewGeometry.MIN_RADIUS,
                configuredCeiling - Math.max(0, adaptivePenalty));
        int requested = Math.min(this.clientRequestedDistance, adaptiveCeiling);
        return Math.max(this.baselineEffectiveDistance, requested);
    }

    RingPlan startPlan(World world, int radius, long currentTick) {
        if (this.plan != null) {
            throw new IllegalStateException("A ring plan is already active");
        }
        if (radius != this.committedDistance + 1) {
            throw new IllegalArgumentException("Rings must be promoted exactly one radius at a time");
        }

        Set<ChunkCoordinate> expected = new HashSet<>();
        for (ChunkOffset offset : PaperViewGeometry.boundaryOffsets(radius)) {
            int chunkX = this.centerX + offset.x();
            int chunkZ = this.centerZ + offset.z();
            expected.add(new ChunkCoordinate(world.getUID(), chunkX, chunkZ));
        }

        this.plan = new RingPlan(
                radius,
                this.generation,
                currentTick,
                new LeaseOwner(this.playerId, this.generation, radius),
                expected);
        return this.plan;
    }

    void applyDistance(Player player, int distance) {
        int clamped = Math.max(PaperViewGeometry.MIN_RADIUS, Math.min(PaperViewGeometry.MAX_RADIUS, distance));
        player.setSendViewDistance(clamped);
        this.lastAppliedSendSetting = clamped;
    }

    void promote(Player player, long currentTick) {
        RingPlan active = Objects.requireNonNull(this.plan, "plan");
        if (!active.barrier.isPreparationComplete()) {
            throw new IllegalStateException("Cannot promote an incomplete preparation barrier");
        }
        applyDistance(player, active.radius);
        active.barrier.openDelivery();
        active.promotedTick = currentTick;
    }

    boolean confirmDelivery(ChunkCoordinate coordinate) {
        RingPlan active = this.plan;
        if (active == null || active.barrier.phase() != CompletenessBarrier.Phase.DELIVERING) {
            return false;
        }
        return active.barrier.markDelivered(coordinate);
    }

    boolean completePlan() {
        RingPlan active = this.plan;
        if (active == null || !active.barrier.isComplete()) {
            return false;
        }
        this.committedDistance = active.radius;
        this.plan = null;
        return true;
    }

    void rollback(Player player, long nextRetryTick) {
        if (this.plan != null && this.plan.barrier.phase() == CompletenessBarrier.Phase.DELIVERING) {
            applyDistance(player, this.committedDistance);
        }
        cancelPlan();
        this.nextRetryTick = nextRetryTick;
    }

    void retract(Player player, int desiredDistance) {
        int next = Math.max(this.baselineEffectiveDistance, desiredDistance);
        if (next < this.committedDistance || this.plan != null) {
            cancelPlan();
            applyDistance(player, next);
            this.committedDistance = next;
            this.generation++;
        }
    }

    void blockRetry(long nextRetryTick) {
        cancelPlan();
        this.nextRetryTick = nextRetryTick;
    }

    void restore(Player player) {
        cancelPlan();
        player.setSendViewDistance(this.baselineSendSetting);
        this.lastAppliedSendSetting = this.baselineSendSetting;
        this.committedDistance = this.baselineEffectiveDistance;
        this.generation++;
    }

    void cancelPlan() {
        if (this.plan != null) {
            this.plan.barrier.cancel();
            this.plan = null;
        }
    }

    private static int resolveEffective(int setting, int serverDefault) {
        int value = setting == -1 ? serverDefault : setting;
        return Math.max(PaperViewGeometry.MIN_RADIUS, Math.min(PaperViewGeometry.MAX_RADIUS, value));
    }

    private static int readClientDistance(Player player, int fallback) {
        try {
            Integer value = player.getClientOption(ClientOption.VIEW_DISTANCE);
            return Math.max(PaperViewGeometry.MIN_RADIUS, Math.min(PaperViewGeometry.MAX_RADIUS, value));
        } catch (IllegalStateException exception) {
            return fallback;
        }
    }

    static final class RingPlan {

        private final int radius;
        private final long generation;
        private final long createdTick;
        private final LeaseOwner owner;
        private final CompletenessBarrier<ChunkCoordinate> barrier;
        private final Set<ChunkCoordinate> requested = new HashSet<>();
        private long promotedTick = -1L;

        private RingPlan(
                int radius,
                long generation,
                long createdTick,
                LeaseOwner owner,
                Set<ChunkCoordinate> expected) {
            this.radius = radius;
            this.generation = generation;
            this.createdTick = createdTick;
            this.owner = owner;
            this.barrier = new CompletenessBarrier<>(expected);
        }

        int radius() {
            return this.radius;
        }

        long generation() {
            return this.generation;
        }

        long createdTick() {
            return this.createdTick;
        }

        LeaseOwner owner() {
            return this.owner;
        }

        long promotedTick() {
            return this.promotedTick;
        }

        CompletenessBarrier<ChunkCoordinate> barrier() {
            return this.barrier;
        }

        boolean markRequested(ChunkCoordinate coordinate) {
            return this.requested.add(coordinate);
        }

        void clearRequested(ChunkCoordinate coordinate) {
            this.requested.remove(coordinate);
        }

        boolean isRequested(ChunkCoordinate coordinate) {
            return this.requested.contains(coordinate);
        }
    }
}
