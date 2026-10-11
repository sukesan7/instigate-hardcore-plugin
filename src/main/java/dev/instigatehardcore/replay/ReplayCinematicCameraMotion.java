package dev.instigatehardcore.replay;

/**
 * Boundary check for protocol-relative movement. Each position component
 * is encoded in a signed short in the Minecraft relative-move packet.
 * Using a conservative 7-block threshold avoids overflow/teleport jitter.
 */
public final class ReplayCinematicCameraMotion {
    private static final double MAX_RELATIVE_DELTA = 7.0;

    private ReplayCinematicCameraMotion() { }

    public static boolean useRelativeMove(
        ReplayCinematicCameraPath.Frame previous,
        ReplayCinematicCameraPath.Frame next
    ) {
        if (previous == null || next == null) return false;
        return Math.abs(next.x() - previous.x()) <= MAX_RELATIVE_DELTA
            && Math.abs(next.y() - previous.y()) <= MAX_RELATIVE_DELTA
            && Math.abs(next.z() - previous.z()) <= MAX_RELATIVE_DELTA;
    }
}
