package io.github.limmeswe.headroom.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkGeometryTest {

    @Test
    void boundariesAreExactUniqueSetDifferences() {
        for (int distance = 2; distance <= 32; distance++) {
            List<ChunkOffset> boundary = ChunkGeometry.sendBoundary(distance);
            assertFalse(boundary.isEmpty());
            assertEquals(boundary.size(), new HashSet<>(boundary).size());

            int extent = distance + 1;
            HashSet<ChunkOffset> expected = new HashSet<>();
            for (int x = -extent; x <= extent; x++) {
                for (int z = -extent; z <= extent; z++) {
                    if (ChunkGeometry.isWithinSendDistance(x, z, distance)
                            && !ChunkGeometry.isWithinSendDistance(x, z, distance - 1)) {
                        expected.add(new ChunkOffset(x, z));
                    }
                }
            }
            assertEquals(expected, new HashSet<>(boundary));
        }
    }

    @Test
    void boundariesAreCachedAndImmutable() {
        List<ChunkOffset> first = ChunkGeometry.sendBoundary(24);
        List<ChunkOffset> second = ChunkGeometry.sendBoundary(24);
        assertSame(first, second);
        assertThrows(UnsupportedOperationException.class, () -> first.add(new ChunkOffset(0, 0)));
    }
}
