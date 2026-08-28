package dev.headroom.core;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A two-stage barrier that prevents a radius from being described as committed until
 * every expected chunk is both prepared and confirmed as delivered by Paper.
 *
 * @param <T> the immutable unit of work, normally a chunk coordinate
 */
public final class CompletenessBarrier<T> {

    public enum Phase {
        PREPARING,
        DELIVERING,
        COMPLETE,
        CANCELLED
    }

    private final Set<T> expected;
    private final Set<T> prepared = new HashSet<>();
    private final Set<T> delivered = new HashSet<>();
    private Phase phase = Phase.PREPARING;

    public CompletenessBarrier(Set<T> expected) {
        Objects.requireNonNull(expected, "expected");
        if (expected.isEmpty()) {
            throw new IllegalArgumentException("A completeness barrier requires at least one expected item");
        }
        this.expected = Set.copyOf(expected);
    }

    public Phase phase() {
        return this.phase;
    }

    public Set<T> expected() {
        return this.expected;
    }

    public boolean markPrepared(T item) {
        requireActive();
        if (this.phase != Phase.PREPARING || !this.expected.contains(item)) {
            return false;
        }
        return this.prepared.add(item);
    }

    public boolean isPreparationComplete() {
        return this.prepared.size() == this.expected.size();
    }

    public Set<T> missingPreparation() {
        Set<T> missing = new HashSet<>(this.expected);
        missing.removeAll(this.prepared);
        return Set.copyOf(missing);
    }

    public void openDelivery() {
        requireActive();
        if (this.phase != Phase.PREPARING) {
            throw new IllegalStateException("Delivery can only open from PREPARING");
        }
        if (!isPreparationComplete()) {
            throw new IllegalStateException("Cannot open delivery before every expected item is prepared");
        }
        this.phase = Phase.DELIVERING;
    }

    public boolean markDelivered(T item) {
        requireActive();
        if (this.phase != Phase.DELIVERING || !this.expected.contains(item)) {
            return false;
        }
        boolean changed = this.delivered.add(item);
        if (this.delivered.size() == this.expected.size()) {
            this.phase = Phase.COMPLETE;
        }
        return changed;
    }

    public Set<T> missingDelivery() {
        Set<T> missing = new HashSet<>(this.expected);
        missing.removeAll(this.delivered);
        return Set.copyOf(missing);
    }

    public boolean isComplete() {
        return this.phase == Phase.COMPLETE;
    }

    public void cancel() {
        if (this.phase != Phase.COMPLETE) {
            this.phase = Phase.CANCELLED;
        }
    }

    private void requireActive() {
        if (this.phase == Phase.CANCELLED) {
            throw new IllegalStateException("Barrier has been cancelled");
        }
    }
}
