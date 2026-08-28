package dev.headroom.runtime;

import dev.headroom.core.ChunkCoordinate;
import dev.headroom.core.ChunkCostModel;
import dev.headroom.metrics.HeadroomMetrics;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Deduplicates Paper asynchronous chunk requests across every player.
 */
public final class ChunkLoadCoordinator implements AutoCloseable {

    public enum Status {
        READY,
        UNGENERATED,
        CAPACITY,
        FAILED,
        STOPPED
    }

    private final Plugin plugin;
    private final WarmLeaseRegistry leases;
    private final ChunkCostModel costModel;
    private final HeadroomMetrics metrics;
    private final int maximumInFlight;
    private final LongSupplier currentTick;
    private final Map<ChunkCoordinate, InFlight> inFlight = new HashMap<>();

    private boolean closed;

    public ChunkLoadCoordinator(
            Plugin plugin,
            WarmLeaseRegistry leases,
            ChunkCostModel costModel,
            HeadroomMetrics metrics,
            int maximumInFlight,
            LongSupplier currentTick) {
        this.plugin = plugin;
        this.leases = leases;
        this.costModel = costModel;
        this.metrics = metrics;
        this.maximumInFlight = maximumInFlight;
        this.currentTick = currentTick;
    }

    public CompletableFuture<Result> prepare(
            ChunkCoordinate coordinate,
            LeaseOwner owner,
            boolean generate) {
        requirePrimaryThread();
        if (this.closed || !this.plugin.isEnabled()) {
            return CompletableFuture.completedFuture(new Result(Status.STOPPED, null));
        }
        if (this.leases.isHeldBy(coordinate, owner)) {
            return CompletableFuture.completedFuture(new Result(Status.READY, null));
        }

        World world = Bukkit.getWorld(coordinate.worldId());
        if (world == null) {
            return CompletableFuture.completedFuture(new Result(Status.FAILED, null));
        }
        if (!generate && !world.isChunkGenerated(coordinate.x(), coordinate.z())) {
            return CompletableFuture.completedFuture(new Result(Status.UNGENERATED, null));
        }
        if (world.isChunkLoaded(coordinate.x(), coordinate.z())) {
            boolean held = this.leases.acquire(
                    world,
                    coordinate,
                    owner,
                    this.currentTick.getAsLong());
            return CompletableFuture.completedFuture(
                    new Result(held ? Status.READY : Status.CAPACITY, null));
        }

        InFlight shared = this.inFlight.get(coordinate);
        if (shared == null) {
            if (this.inFlight.size() >= this.maximumInFlight) {
                return CompletableFuture.completedFuture(new Result(Status.CAPACITY, null));
            }
            long startedNanos = System.nanoTime();
            CompletableFuture<Chunk> future =
                    world.getChunkAtAsync(coordinate.x(), coordinate.z(), generate);
            shared = new InFlight(future, startedNanos);
            this.inFlight.put(coordinate, shared);
            this.metrics.loadStarted();

            InFlight created = shared;
            future.whenComplete((chunk, throwable) -> {
                requirePrimaryThread();
                this.inFlight.remove(coordinate, created);
                this.costModel.record(coordinate, System.nanoTime() - created.startedNanos);
                if (throwable == null && chunk != null) {
                    this.metrics.loadSucceeded();
                } else {
                    this.metrics.loadFailed();
                }
            });
        } else {
            this.metrics.sharedLoadJoined();
        }

        CompletableFuture<Result> result = new CompletableFuture<>();
        shared.future.whenComplete((chunk, throwable) -> {
            requirePrimaryThread();
            if (this.closed || !this.plugin.isEnabled()) {
                result.complete(new Result(Status.STOPPED, throwable));
                return;
            }
            if (throwable != null || chunk == null) {
                result.complete(new Result(Status.FAILED, throwable));
                return;
            }
            boolean held = this.leases.acquire(
                    world,
                    coordinate,
                    owner,
                    this.currentTick.getAsLong());
            result.complete(new Result(held ? Status.READY : Status.CAPACITY, null));
        });
        return result;
    }

    public int inFlightCount() {
        return this.inFlight.size();
    }

    @Override
    public void close() {
        requirePrimaryThread();
        this.closed = true;
        this.inFlight.clear();
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Chunk load coordination must run on the server thread");
        }
    }

    private record InFlight(CompletableFuture<Chunk> future, long startedNanos) {
    }

    public record Result(Status status, Throwable failure) {
    }
}
