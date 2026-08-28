package io.github.limmeswe.headroom.core;

/**
 * Relative chunk coordinate used by precomputed horizon geometry.
 */
public record ChunkOffset(int x, int z) {

    public long squaredDistance() {
        return (long) x * x + (long) z * z;
    }
}
