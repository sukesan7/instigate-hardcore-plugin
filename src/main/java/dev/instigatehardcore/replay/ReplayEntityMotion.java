package dev.instigatehardcore.replay;

/**
 * Side-effect-free packet motion planner shared by replay ghosts and the
 * cinematic camera. A 26.3 relative-position packet has bounded, quantized
 * components; large jumps must use absolute teleport/sync instead.
 */
public final class ReplayEntityMotion {
    private static final double MAX_RELATIVE_AXIS = 7.0;

    private ReplayEntityMotion() { }

    public enum Kind { NONE, RELATIVE, TELEPORT }

    public record Plan(Kind kind, double dx, double dy, double dz) {
        public Plan {
            if (kind == null) throw new IllegalArgumentException("Missing move kind");
        }
    }

    public static Plan plan(
        double sentX, double sentY, double sentZ,
        double targetX, double targetY, double targetZ,
        float previousYaw, float previousPitch,
        float targetYaw, float targetPitch
    ) {
        if (!Double.isFinite(sentX) || !Double.isFinite(sentY) || !Double.isFinite(sentZ)
            || !Double.isFinite(targetX) || !Double.isFinite(targetY)
            || !Double.isFinite(targetZ)
            || !Float.isFinite(previousYaw) || !Float.isFinite(previousPitch)
            || !Float.isFinite(targetYaw) || !Float.isFinite(targetPitch)) {
            throw new IllegalArgumentException("Replay movement must be finite");
        }
        double dx = targetX - sentX;
        double dy = targetY - sentY;
        double dz = targetZ - sentZ;
        if (dx == 0.0 && dy == 0.0 && dz == 0.0
            && previousYaw == targetYaw && previousPitch == targetPitch) {
            return new Plan(Kind.NONE, 0.0, 0.0, 0.0);
        }
        if (Math.abs(dx) <= MAX_RELATIVE_AXIS
            && Math.abs(dy) <= MAX_RELATIVE_AXIS
            && Math.abs(dz) <= MAX_RELATIVE_AXIS) {
            return new Plan(Kind.RELATIVE, dx, dy, dz);
        }
        return new Plan(Kind.TELEPORT, dx, dy, dz);
    }
}
