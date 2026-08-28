package dev.headroom.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reproduces the horizontal inclusion rule used by Paper's player chunk sender.
 *
 * <p>The extra two-chunk shoulder is important: using a simple square or circle creates
 * boundary chunks that Paper will never send, which would make a completeness barrier
 * impossible to satisfy.</p>
 */
public final class PaperViewGeometry {

    public static final int MIN_RADIUS = 2;
    public static final int MAX_RADIUS = 32;

    private static final Comparator<ChunkOffset> OFFSET_ORDER =
            Comparator.comparingLong(ChunkOffset::distanceSquared)
                    .thenComparingInt(ChunkOffset::x)
                    .thenComparingInt(ChunkOffset::z);
    private static final Map<Integer, List<ChunkOffset>> VISIBLE_CACHE = new ConcurrentHashMap<>();
    private static final Map<Integer, List<ChunkOffset>> BOUNDARY_CACHE = new ConcurrentHashMap<>();

    private PaperViewGeometry() {
    }

    public static boolean contains(int offsetX, int offsetZ, int radius) {
        if (radius < 0) {
            return false;
        }

        int absoluteX = Math.abs(offsetX);
        int absoluteZ = Math.abs(offsetZ);
        if (Math.max(absoluteX, absoluteZ) > radius + 1) {
            return false;
        }

        long shoulderX = Math.max(0, absoluteX - 2);
        long shoulderZ = Math.max(0, absoluteZ - 2);
        return shoulderX * shoulderX + shoulderZ * shoulderZ < (long) radius * radius;
    }

    public static List<ChunkOffset> visibleOffsets(int radius) {
        validateRadius(radius);
        return VISIBLE_CACHE.computeIfAbsent(radius, PaperViewGeometry::computeVisibleOffsets);
    }

    public static List<ChunkOffset> boundaryOffsets(int radius) {
        validateRadius(radius);
        return BOUNDARY_CACHE.computeIfAbsent(radius, PaperViewGeometry::computeBoundaryOffsets);
    }

    private static List<ChunkOffset> computeVisibleOffsets(int radius) {
        List<ChunkOffset> offsets = new ArrayList<>();
        int limit = radius + 1;
        for (int x = -limit; x <= limit; x++) {
            for (int z = -limit; z <= limit; z++) {
                if (contains(x, z, radius)) {
                    offsets.add(new ChunkOffset(x, z));
                }
            }
        }
        offsets.sort(OFFSET_ORDER);
        return List.copyOf(offsets);
    }

    private static List<ChunkOffset> computeBoundaryOffsets(int radius) {
        List<ChunkOffset> offsets = new ArrayList<>();
        for (ChunkOffset offset : visibleOffsets(radius)) {
            if (!contains(offset.x(), offset.z(), radius - 1)) {
                offsets.add(offset);
            }
        }
        offsets.sort(OFFSET_ORDER);
        return List.copyOf(offsets);
    }

    private static void validateRadius(int radius) {
        if (radius < MIN_RADIUS || radius > MAX_RADIUS) {
            throw new IllegalArgumentException(
                    "radius must be between " + MIN_RADIUS + " and " + MAX_RADIUS + ": " + radius);
        }
    }
}
