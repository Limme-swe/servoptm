package dev.headroom.core;

/**
 * Orders optional work using a conservative reachability envelope.
 *
 * <p>The predictor never removes chunks from a required ring. Direction only changes
 * the order in which an already complete ring is prepared, so an instant turn cannot
 * create a visual quality hole.</p>
 */
public final class ReachabilityPredictor {

    private static final double EPSILON = 1.0e-8;

    private ReachabilityPredictor() {
    }

    public static double priority(ChunkOffset offset, Motion motion) {
        double offsetLength = Math.hypot(offset.x(), offset.z());
        if (offsetLength < EPSILON || motion.speed() < EPSILON) {
            return 0.35;
        }

        double motionLength = Math.hypot(motion.x(), motion.z());
        if (motionLength < EPSILON) {
            return 0.35;
        }

        double alignment =
                (offset.x() * motion.x() + offset.z() * motion.z()) / (offsetLength * motionLength);
        double forward = Math.max(0.0, alignment);
        double turnSafeFloor = 0.25;
        double speedWeight = Math.min(1.0, motion.speed() / 1.1);
        double nearWeight = 1.0 / (1.0 + offsetLength * 0.08);
        return turnSafeFloor + forward * (0.55 + 0.45 * speedWeight) + nearWeight * 0.25;
    }

    public record Motion(double x, double z, double speed) {

        public static Motion stationary() {
            return new Motion(0.0, 0.0, 0.0);
        }
    }
}
