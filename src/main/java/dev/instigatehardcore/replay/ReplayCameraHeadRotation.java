package dev.instigatehardcore.replay;

/**
 * Detect when the camera rig's independently tracked head yaw needs updating.
 * Kept pure so rotation-only replay frames can be regression tested without
 * spawning packet entities or a Paper server.
 */
public final class ReplayCameraHeadRotation {
    private ReplayCameraHeadRotation() { }

    public static boolean changed(float previousYaw, float currentYaw) {
        if (!Float.isFinite(previousYaw) || !Float.isFinite(currentYaw)) {
            throw new IllegalArgumentException("Camera head yaw must be finite.");
        }
        // Do not normalize before comparison: Minecraft angle packets encode
        // the orientation, and this also catches 180 -> -180 transitions.
        return Float.compare(previousYaw, currentYaw) != 0;
    }
}
