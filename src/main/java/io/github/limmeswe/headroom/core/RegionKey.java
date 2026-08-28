package io.github.limmeswe.headroom.core;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of one Anvil region (32 by 32 chunks).
 */
public record RegionKey(UUID worldId, int x, int z) {

    public RegionKey {
        Objects.requireNonNull(worldId, "worldId");
    }
}
