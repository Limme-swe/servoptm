package dev.headroom.runtime;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifies one immutable player ring plan for warm-ticket ownership.
 */
public record LeaseOwner(UUID playerId, long generation, int radius) {

    public LeaseOwner {
        Objects.requireNonNull(playerId, "playerId");
    }
}
