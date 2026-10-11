package dev.instigatehardcore.replay;

import java.util.Objects;

/** Client-only block dust associated with a recorded player pose. */
public record ReplayMovementEffect(Kind kind, ReplayActorPose pose, int particleCount) {
    public enum Kind { SPRINT_DUST, LANDING_DUST }

    public ReplayMovementEffect {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(pose);
        if (particleCount < 1 || particleCount > 48) {
            throw new IllegalArgumentException("Unsafe particle count.");
        }
    }
}
