package io.github.limmeswe.headroom.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RingBarrierTest {

    @Test
    void requiresEveryChunkAndSupportsReadinessRevocation() {
        UUID worldId = UUID.randomUUID();
        ChunkKey first = new ChunkKey(worldId, 4, 5);
        ChunkKey second = new ChunkKey(worldId, 5, 5);
        RingBarrier barrier = new RingBarrier(worldId, 4, 4, 2, 100L, Set.of(first, second));

        assertTrue(barrier.markReady(first));
        assertFalse(barrier.isComplete());
        assertTrue(barrier.markReady(second));
        assertTrue(barrier.isComplete());

        assertTrue(barrier.markNotReady(first));
        assertFalse(barrier.isComplete());
        assertEquals(1, barrier.pendingCount());
    }

    @Test
    void unavailableChunksBlockUntilRetried() {
        UUID worldId = UUID.randomUUID();
        ChunkKey chunk = new ChunkKey(worldId, 1, 1);
        RingBarrier barrier = new RingBarrier(worldId, 0, 0, 2, 100L, Set.of(chunk));

        assertTrue(barrier.markUnavailable(chunk));
        assertTrue(barrier.isBlocked());
        assertTrue(barrier.retryUnavailable(chunk));
        assertFalse(barrier.isBlocked());
        assertEquals(1, barrier.pendingCount());
    }
}
