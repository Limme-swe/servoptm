package io.github.limmeswe.headroom.core;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable identity for a chunk across all loaded worlds.
 */
public record ChunkKey(UUID worldId, int x, int z) implements Comparable<ChunkKey> {

    public ChunkKey {
        Objects.requireNonNull(worldId, "worldId");
    }

    public long packedCoordinates() {
        return Integer.toUnsignedLong(x) | (Integer.toUnsignedLong(z) << 32);
    }

    public RegionKey region() {
        return new RegionKey(worldId, Math.floorDiv(x, 32), Math.floorDiv(z, 32));
    }

    public long squaredDistanceTo(int otherX, int otherZ) {
        long deltaX = (long) x - otherX;
        long deltaZ = (long) z - otherZ;
        return deltaX * deltaX + deltaZ * deltaZ;
    }

    @Override
    public int compareTo(ChunkKey other) {
        int worldComparison = worldId.compareTo(other.worldId);
        if (worldComparison != 0) {
            return worldComparison;
        }
        int xComparison = Integer.compare(x, other.x);
        return xComparison != 0 ? xComparison : Integer.compare(z, other.z);
    }
}
