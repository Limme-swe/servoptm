package io.github.limmeswe.headroom.core;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Completion barrier for one exact 360-degree send-distance boundary.
 */
public final class RingBarrier {

    private final UUID worldId;
    private final int centerX;
    private final int centerZ;
    private final int radius;
    private final long createdAtNanos;
    private final Set<ChunkKey> required;
    private final Set<ChunkKey> pending;
    private final Set<ChunkKey> ready = new LinkedHashSet<>();
    private final Set<ChunkKey> unavailable = new LinkedHashSet<>();

    public RingBarrier(
            UUID worldId,
            int centerX,
            int centerZ,
            int radius,
            long createdAtNanos,
            Set<ChunkKey> requiredChunks
    ) {
        this.worldId = Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(requiredChunks, "requiredChunks");
        if (radius < 2) {
            throw new IllegalArgumentException("radius must be at least 2");
        }
        if (requiredChunks.isEmpty()) {
            throw new IllegalArgumentException("requiredChunks must not be empty");
        }
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius = radius;
        this.createdAtNanos = createdAtNanos;
        required = Collections.unmodifiableSet(new LinkedHashSet<>(requiredChunks));
        pending = new LinkedHashSet<>(requiredChunks);
    }

    public boolean matches(UUID candidateWorldId, int candidateCenterX, int candidateCenterZ, int candidateRadius) {
        return worldId.equals(candidateWorldId)
                && centerX == candidateCenterX
                && centerZ == candidateCenterZ
                && radius == candidateRadius;
    }

    public boolean markReady(ChunkKey chunk) {
        if (!required.contains(chunk)) {
            return false;
        }
        pending.remove(chunk);
        unavailable.remove(chunk);
        return ready.add(chunk);
    }

    public boolean markNotReady(ChunkKey chunk) {
        if (!ready.remove(chunk)) {
            return false;
        }
        pending.add(chunk);
        return true;
    }

    public boolean markUnavailable(ChunkKey chunk) {
        if (!required.contains(chunk)) {
            return false;
        }
        pending.remove(chunk);
        ready.remove(chunk);
        return unavailable.add(chunk);
    }

    public boolean retryUnavailable(ChunkKey chunk) {
        if (!unavailable.remove(chunk)) {
            return false;
        }
        pending.add(chunk);
        return true;
    }

    public boolean contains(ChunkKey chunk) {
        return required.contains(chunk);
    }

    public boolean isComplete() {
        return ready.size() == required.size();
    }

    public boolean isBlocked() {
        return !unavailable.isEmpty();
    }

    public int requiredCount() {
        return required.size();
    }

    public int readyCount() {
        return ready.size();
    }

    public int pendingCount() {
        return pending.size();
    }

    public int unavailableCount() {
        return unavailable.size();
    }

    public Set<ChunkKey> requiredChunks() {
        return required;
    }

    public Set<ChunkKey> pendingChunks() {
        return Collections.unmodifiableSet(pending);
    }

    public Set<ChunkKey> readyChunks() {
        return Collections.unmodifiableSet(ready);
    }

    public Set<ChunkKey> unavailableChunks() {
        return Collections.unmodifiableSet(unavailable);
    }

    public UUID worldId() {
        return worldId;
    }

    public int centerX() {
        return centerX;
    }

    public int centerZ() {
        return centerZ;
    }

    public int radius() {
        return radius;
    }

    public long ageNanos(long nowNanos) {
        return Math.max(0L, nowNanos - createdAtNanos);
    }
}
