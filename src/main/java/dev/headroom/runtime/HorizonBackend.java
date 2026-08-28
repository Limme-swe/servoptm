package dev.headroom.runtime;

import dev.headroom.core.ChunkCoordinate;
import java.util.concurrent.CompletableFuture;

/**
 * Isolates the preparation mechanism from HorizonGraph.
 */
interface HorizonBackend extends AutoCloseable {

    String name();

    boolean preservesStandardPacketPath();

    CompletableFuture<ChunkLoadCoordinator.Result> prepare(
            ChunkCoordinate coordinate,
            LeaseOwner owner,
            boolean generate);

    int inFlightCount();

    @Override
    void close();
}
