package dev.headroom.runtime;

import dev.headroom.core.ChunkCoordinate;
import java.util.concurrent.CompletableFuture;

/**
 * Stable backend that lets Paper construct and send every chunk packet.
 */
final class PaperNativeBackend implements HorizonBackend {

    private final ChunkLoadCoordinator coordinator;

    PaperNativeBackend(ChunkLoadCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Override
    public String name() {
        return "paper-native";
    }

    @Override
    public boolean preservesStandardPacketPath() {
        return true;
    }

    @Override
    public CompletableFuture<ChunkLoadCoordinator.Result> prepare(
            ChunkCoordinate coordinate,
            LeaseOwner owner,
            boolean generate) {
        return this.coordinator.prepare(coordinate, owner, generate);
    }

    @Override
    public int inFlightCount() {
        return this.coordinator.inFlightCount();
    }

    @Override
    public void close() {
        this.coordinator.close();
    }
}
