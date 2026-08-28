package io.github.limmeswe.headroom.core;

/**
 * Provides a small locality bonus without allowing disk locality to overtake deadlines.
 */
@FunctionalInterface
public interface RegionAffinity {

    double bonusFor(RegionKey region);

    static RegionAffinity none() {
        return ignored -> 0.0;
    }
}
