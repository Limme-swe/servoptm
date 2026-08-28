package dev.headroom.core;

import java.util.Objects;
import java.util.UUID;

/**
 * A globally unique chunk coordinate.
 */
public record ChunkCoordinate(UUID worldId, int x, int z) {

    public ChunkCoordinate {
        Objects.requireNonNull(worldId, "worldId");
    }

    public long packedLocal() {
        return ((long) this.z << 32) | (this.x & 0xffff_ffffL);
    }

    public RegionCoordinate region() {
        return new RegionCoordinate(this.worldId, Math.floorDiv(this.x, 32), Math.floorDiv(this.z, 32));
    }

    public record RegionCoordinate(UUID worldId, int x, int z) {

        public RegionCoordinate {
            Objects.requireNonNull(worldId, "worldId");
        }
    }
}
