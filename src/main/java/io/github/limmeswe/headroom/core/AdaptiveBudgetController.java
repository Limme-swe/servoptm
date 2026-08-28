package io.github.limmeswe.headroom.core;

import java.util.Objects;

/**
 * Hysteretic controller that converts rolling MSPT percentiles into safe work budgets.
 */
public final class AdaptiveBudgetController {

    private final Config config;
    private final SlidingPercentileWindow ticks;

    private State state = State.RECOVERING;
    private int healthyWindows;
    private int overloadedWindows;
    private Decision lastDecision;

    public AdaptiveBudgetController(Config config) {
        this.config = Objects.requireNonNull(config, "config");
        ticks = new SlidingPercentileWindow(config.windowTicks());
        lastDecision = new Decision(state, 0, 0.0, 0.0, 0.0, 0.20, 0.25, false);
    }

    public void observeTick(double tickDurationMillis) {
        ticks.add(tickDurationMillis);
    }

    public Decision evaluate() {
        SlidingPercentileWindow.Snapshot snapshot = ticks.snapshot();
        if (snapshot.samples() < config.minimumSamples()) {
            state = State.RECOVERING;
            lastDecision = decision(snapshot, state);
            return lastDecision;
        }

        boolean critical = snapshot.p99() >= config.hardP99Millis();
        boolean overloaded = snapshot.p95() >= config.targetP95Millis()
                || snapshot.p99() >= config.targetP99Millis();

        if (critical) {
            overloadedWindows++;
            healthyWindows = 0;
            state = overloadedWindows >= config.criticalWindows() ? State.CRITICAL : State.CONSTRAINED;
        } else if (overloaded) {
            overloadedWindows++;
            healthyWindows = 0;
            state = State.CONSTRAINED;
        } else {
            overloadedWindows = 0;
            healthyWindows++;
            if (healthyWindows >= config.recoveryWindows()) {
                state = State.HEALTHY;
            } else if (state != State.HEALTHY) {
                state = State.RECOVERING;
            }
        }

        lastDecision = decision(snapshot, state);
        return lastDecision;
    }

    public Decision currentDecision() {
        return lastDecision;
    }

    private static Decision decision(SlidingPercentileWindow.Snapshot snapshot, State state) {
        return switch (state) {
            case HEALTHY -> new Decision(
                    state, snapshot.samples(), snapshot.mean(), snapshot.p95(), snapshot.p99(),
                    1.0, 1.0, true);
            case CONSTRAINED -> new Decision(
                    state, snapshot.samples(), snapshot.mean(), snapshot.p95(), snapshot.p99(),
                    0.40, 0.55, false);
            case CRITICAL -> new Decision(
                    state, snapshot.samples(), snapshot.mean(), snapshot.p95(), snapshot.p99(),
                    0.0, 0.0, false);
            case RECOVERING -> new Decision(
                    state, snapshot.samples(), snapshot.mean(), snapshot.p95(), snapshot.p99(),
                    0.20, 0.25, false);
        };
    }

    public enum State {
        HEALTHY,
        CONSTRAINED,
        CRITICAL,
        RECOVERING
    }

    public record Config(
            int windowTicks,
            int minimumSamples,
            double targetP95Millis,
            double targetP99Millis,
            double hardP99Millis,
            int recoveryWindows,
            int criticalWindows
    ) {

        public Config {
            if (windowTicks < 40) {
                throw new IllegalArgumentException("windowTicks must be at least 40");
            }
            if (minimumSamples < 20 || minimumSamples > windowTicks) {
                throw new IllegalArgumentException("minimumSamples must be between 20 and windowTicks");
            }
            if (!Double.isFinite(targetP95Millis) || targetP95Millis <= 0.0) {
                throw new IllegalArgumentException("targetP95Millis must be finite and positive");
            }
            if (!Double.isFinite(targetP99Millis) || targetP99Millis < targetP95Millis) {
                throw new IllegalArgumentException("targetP99Millis must be at least targetP95Millis");
            }
            if (!Double.isFinite(hardP99Millis) || hardP99Millis < targetP99Millis) {
                throw new IllegalArgumentException("hardP99Millis must be at least targetP99Millis");
            }
            if (recoveryWindows < 1 || criticalWindows < 1) {
                throw new IllegalArgumentException("window hysteresis values must be positive");
            }
        }
    }

    public record Decision(
            State state,
            int samples,
            double meanMspt,
            double p95Mspt,
            double p99Mspt,
            double workMultiplier,
            double extensionFraction,
            boolean allowSpeculativeWork
    ) {
    }
}
