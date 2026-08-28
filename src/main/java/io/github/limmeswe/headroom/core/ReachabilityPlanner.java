package io.github.limmeswe.headroom.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.ToDoubleFunction;

/**
 * Builds a complete guaranteed boundary and a separate opportunistic envelope.
 */
public final class ReachabilityPlanner {

    private static final Comparator<Candidate> CANDIDATE_ORDER = Comparator
            .<Candidate>comparingInt(candidate -> candidate.demandClass().rank()).reversed()
            .thenComparing(Comparator.comparingDouble(Candidate::alignment).reversed())
            .thenComparingInt(Candidate::radius)
            .thenComparing(candidate -> candidate.contribution().chunk());

    private final Config config;

    public ReachabilityPlanner(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public Plan plan(PlayerMotion motion, ToDoubleFunction<ChunkKey> predictedCost) {
        Objects.requireNonNull(motion, "motion");
        Objects.requireNonNull(predictedCost, "predictedCost");

        if (motion.committedDistance() >= motion.maximumDistance()) {
            return Plan.empty();
        }

        int nextRadius = motion.committedDistance() + 1;
        List<DemandContribution> commitRing = new ArrayList<>();
        long commitDeadline = deadlineForRadius(motion, nextRadius);
        for (ChunkOffset offset : ChunkGeometry.sendBoundary(nextRadius)) {
            ChunkKey key = new ChunkKey(
                    motion.worldId(),
                    motion.chunkX() + offset.x(),
                    motion.chunkZ() + offset.z()
            );
            commitRing.add(new DemandContribution(
                    motion.playerId(),
                    key,
                    DemandClass.COMMIT_RING,
                    nextRadius,
                    commitDeadline,
                    sanitizeCost(predictedCost.applyAsDouble(key)),
                    ChunkGeometry.alignment(offset.x(), offset.z(), motion.velocityX(), motion.velocityZ())
            ));
        }

        if (motion.speedBlocksPerSecond() < config.minimumSpeculationSpeed()) {
            return new Plan(List.copyOf(commitRing), List.of());
        }

        int finalRadius = Math.min(motion.maximumDistance(), nextRadius + config.speculativeRings());
        List<Candidate> candidates = new ArrayList<>();
        for (int radius = nextRadius + 1; radius <= finalRadius; radius++) {
            for (ChunkOffset offset : ChunkGeometry.sendBoundary(radius)) {
                double alignment = ChunkGeometry.alignment(
                        offset.x(), offset.z(), motion.velocityX(), motion.velocityZ());
                if (alignment < effectiveMinimumAlignment(motion)) {
                    continue;
                }

                double secondsToReach = secondsToReach(motion, radius);
                DemandClass demandClass = secondsToReach <= config.reachabilityHorizonSeconds()
                        ? DemandClass.REACHABILITY
                        : DemandClass.SPECULATIVE;
                ChunkKey key = new ChunkKey(
                        motion.worldId(),
                        motion.chunkX() + offset.x(),
                        motion.chunkZ() + offset.z()
                );
                DemandContribution contribution = new DemandContribution(
                        motion.playerId(),
                        key,
                        demandClass,
                        radius,
                        motion.sampledAtNanos() + secondsToNanos(secondsToReach),
                        sanitizeCost(predictedCost.applyAsDouble(key)),
                        alignment
                );
                candidates.add(new Candidate(contribution, demandClass, alignment, radius));
            }
        }

        candidates.sort(CANDIDATE_ORDER);
        int count = Math.min(config.maximumSpeculativeCandidates(), candidates.size());
        List<DemandContribution> opportunistic = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            opportunistic.add(candidates.get(index).contribution());
        }
        return new Plan(List.copyOf(commitRing), List.copyOf(opportunistic));
    }

    private double effectiveMinimumAlignment(PlayerMotion motion) {
        double widened = config.minimumDirectionalAlignment() - motion.turnUncertainty() * 0.65;
        return Math.max(-0.50, widened);
    }

    private long deadlineForRadius(PlayerMotion motion, int radius) {
        double seconds = Math.min(
                config.maximumCommitDeadlineSeconds(),
                Math.max(config.minimumCommitDeadlineSeconds(), secondsToReach(motion, radius))
        );
        return motion.sampledAtNanos() + secondsToNanos(seconds);
    }

    private static double secondsToReach(PlayerMotion motion, int radius) {
        double distanceBlocks = Math.max(8.0, (radius - motion.committedDistance()) * 16.0);
        double effectiveSpeed = Math.max(3.0, motion.speedBlocksPerSecond());
        return distanceBlocks / effectiveSpeed;
    }

    private static long secondsToNanos(double seconds) {
        double bounded = Math.max(0.05, Math.min(60.0, seconds));
        return (long) (bounded * 1_000_000_000.0);
    }

    private static double sanitizeCost(double value) {
        return Double.isFinite(value) && value > 0.0 ? value : 1.0;
    }

    public record Config(
            int speculativeRings,
            int maximumSpeculativeCandidates,
            double minimumSpeculationSpeed,
            double minimumDirectionalAlignment,
            double reachabilityHorizonSeconds,
            double minimumCommitDeadlineSeconds,
            double maximumCommitDeadlineSeconds
    ) {

        public Config {
            if (speculativeRings < 0 || speculativeRings > 16) {
                throw new IllegalArgumentException("speculativeRings must be between 0 and 16");
            }
            if (maximumSpeculativeCandidates < 0) {
                throw new IllegalArgumentException("maximumSpeculativeCandidates must be non-negative");
            }
            if (!Double.isFinite(minimumSpeculationSpeed) || minimumSpeculationSpeed < 0.0) {
                throw new IllegalArgumentException("minimumSpeculationSpeed must be finite and non-negative");
            }
            if (!Double.isFinite(minimumDirectionalAlignment)
                    || minimumDirectionalAlignment < -1.0
                    || minimumDirectionalAlignment > 1.0) {
                throw new IllegalArgumentException("minimumDirectionalAlignment must be between -1 and 1");
            }
            if (!Double.isFinite(reachabilityHorizonSeconds) || reachabilityHorizonSeconds <= 0.0) {
                throw new IllegalArgumentException("reachabilityHorizonSeconds must be finite and positive");
            }
            if (!Double.isFinite(minimumCommitDeadlineSeconds) || minimumCommitDeadlineSeconds <= 0.0) {
                throw new IllegalArgumentException("minimumCommitDeadlineSeconds must be finite and positive");
            }
            if (!Double.isFinite(maximumCommitDeadlineSeconds)
                    || maximumCommitDeadlineSeconds < minimumCommitDeadlineSeconds) {
                throw new IllegalArgumentException(
                        "maximumCommitDeadlineSeconds must be finite and at least the minimum");
            }
        }
    }

    public record Plan(List<DemandContribution> commitRing, List<DemandContribution> opportunistic) {

        public Plan {
            commitRing = List.copyOf(commitRing);
            opportunistic = List.copyOf(opportunistic);
        }

        public static Plan empty() {
            return new Plan(List.of(), List.of());
        }

        public List<DemandContribution> all() {
            List<DemandContribution> all = new ArrayList<>(commitRing.size() + opportunistic.size());
            all.addAll(commitRing);
            all.addAll(opportunistic);
            return List.copyOf(all);
        }
    }

    private record Candidate(
            DemandContribution contribution,
            DemandClass demandClass,
            double alignment,
            int radius
    ) {
    }
}
