package dev.headroom.core;

/**
 * An immutable chunk offset relative to a player-centred view.
 */
public record ChunkOffset(int x, int z) {

    public long distanceSquared() {
        return (long) this.x * this.x + (long) this.z * this.z;
    }

    public long packed() {
        return ((long) this.z << 32) | (this.x & 0xffff_ffffL);
    }
}
