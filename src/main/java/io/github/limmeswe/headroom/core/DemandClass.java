package io.github.limmeswe.headroom.core;

/**
 * Semantic class of a chunk request. Higher rank is more important.
 */
public enum DemandClass {
    /** The chunk completes the next guaranteed, contiguous viewing boundary. */
    COMMIT_RING(4, 18.0),
    /** The chunk lies inside the player's conservative reachability envelope. */
    REACHABILITY(3, 8.0),
    /** The chunk is useful because multiple players are converging on it. */
    SHARED_BACKFILL(2, 5.0),
    /** Opportunistic work that may improve a future turn or route. */
    SPECULATIVE(1, 2.0);

    private final int rank;
    private final double baseUtility;

    DemandClass(int rank, double baseUtility) {
        this.rank = rank;
        this.baseUtility = baseUtility;
    }

    public int rank() {
        return rank;
    }

    public double baseUtility() {
        return baseUtility;
    }

    public static DemandClass strongerOf(DemandClass left, DemandClass right) {
        return left.rank >= right.rank ? left : right;
    }
}
