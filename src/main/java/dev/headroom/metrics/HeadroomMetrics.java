package dev.headroom.metrics;

import java.util.concurrent.atomic.LongAdder;

/**
 * Low-overhead lifetime counters used by the status command and benchmarks.
 */
public final class HeadroomMetrics {

    private final LongAdder loadStarts = new LongAdder();
    private final LongAdder sharedLoadJoins = new LongAdder();
    private final LongAdder loadSuccesses = new LongAdder();
    private final LongAdder loadFailures = new LongAdder();
    private final LongAdder ringPromotions = new LongAdder();
    private final LongAdder ringCompletions = new LongAdder();
    private final LongAdder ringRollbacks = new LongAdder();

    public void loadStarted() {
        this.loadStarts.increment();
    }

    public void sharedLoadJoined() {
        this.sharedLoadJoins.increment();
    }

    public void loadSucceeded() {
        this.loadSuccesses.increment();
    }

    public void loadFailed() {
        this.loadFailures.increment();
    }

    public void ringPromoted() {
        this.ringPromotions.increment();
    }

    public void ringCompleted() {
        this.ringCompletions.increment();
    }

    public void ringRolledBack() {
        this.ringRollbacks.increment();
    }

    public Snapshot snapshot() {
        return new Snapshot(
                this.loadStarts.sum(),
                this.sharedLoadJoins.sum(),
                this.loadSuccesses.sum(),
                this.loadFailures.sum(),
                this.ringPromotions.sum(),
                this.ringCompletions.sum(),
                this.ringRollbacks.sum());
    }

    public record Snapshot(
            long loadStarts,
            long sharedLoadJoins,
            long loadSuccesses,
            long loadFailures,
            long ringPromotions,
            long ringCompletions,
            long ringRollbacks) {
    }
}
