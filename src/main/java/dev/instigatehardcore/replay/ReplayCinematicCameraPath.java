package dev.instigatehardcore.replay;

/**
 * Pure Minecraft-coordinate camera path for a third-person deathcam.
 *
 * Yaw 0 faces +Z; positive yaw rotates toward -X. The camera follows
 * behind the recorded victim and looks toward the victim's shoulders.
 * No Bukkit objects or live-entity state are needed to calculate frames.
 */
public final class ReplayCinematicCameraPath {
    private ReplayCinematicCameraPath() { }

    public record Frame(double x, double y, double z, float yaw, float pitch) {
        public Frame {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                throw new IllegalArgumentException("Camera frame must be finite.");
            }
        }
    }

    public static Frame desired(
        double x, double y, double z, float victimYaw,
        double followDistance, double cameraHeight, double shoulderOffset
    ) {
        if (!Double.isFinite(victimYaw) || !Double.isFinite(followDistance)
            || !Double.isFinite(cameraHeight) || !Double.isFinite(shoulderOffset)
            || followDistance < 1 || followDistance > 12
            || cameraHeight < 0.5 || cameraHeight > 8
            || Math.abs(shoulderOffset) > 4) {
            throw new IllegalArgumentException("Invalid cinematic camera geometry.");
        }
        double radians = Math.toRadians(victimYaw);
        double forwardX = -Math.sin(radians);
        double forwardZ = Math.cos(radians);
        double rightX = Math.cos(radians);
        double rightZ = Math.sin(radians);
        double cameraX = x - forwardX * followDistance + rightX * shoulderOffset;
        double cameraY = y + cameraHeight;
        double cameraZ = z - forwardZ * followDistance + rightZ * shoulderOffset;
        return lookAt(cameraX, cameraY, cameraZ, x, y + 1.28, z);
    }

    /** Look from camera coordinates toward a point using Bukkit yaw/pitch. */
    public static Frame lookAt(
        double x, double y, double z,
        double lookX, double lookY, double lookZ
    ) {
        double dx = lookX - x;
        double dz = lookZ - z;
        double horizontal = Math.hypot(dx, dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(lookY - y, horizontal));
        return new Frame(x, y, z, yaw, pitch);
    }

    /**
     * Smooth position and rotation, taking the shortest route around the yaw
     * boundary. Very large jumps (dimension-like movements) snap immediately.
     */
    public static Frame smooth(Frame previous, Frame goal, double alpha) {
        if (!Double.isFinite(alpha) || alpha <= 0 || alpha > 1) {
            throw new IllegalArgumentException("Smoothing alpha must be (0, 1].");
        }
        if (previous == null) return goal;
        double dx = goal.x() - previous.x();
        double dy = goal.y() - previous.y();
        double dz = goal.z() - previous.z();
        if (dx * dx + dy * dy + dz * dz > 24.0 * 24.0) return goal;
        return new Frame(
            previous.x() + dx * alpha,
            previous.y() + dy * alpha,
            previous.z() + dz * alpha,
            interpolateAngle(previous.yaw(), goal.yaw(), alpha),
            (float) (previous.pitch() + (goal.pitch() - previous.pitch()) * alpha)
        );
    }

    private static float interpolateAngle(float a, float b, double alpha) {
        double difference = ((b - a + 540.0) % 360.0) - 180.0;
        return (float) (a + difference * alpha);
    }
}
