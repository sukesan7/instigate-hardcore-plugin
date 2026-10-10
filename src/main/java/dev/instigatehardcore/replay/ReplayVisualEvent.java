package dev.instigatehardcore.replay;

import java.util.Objects;
import java.util.UUID;

/**
 * An observed visual event from an ACTIVE attempt. No Bukkit references.
 * SWING events include misses; HURT/CRITICAL come from confirmed damage.
 */
public record ReplayVisualEvent(
    long tick,
    int attempt,
    UUID worldId,
    UUID actorId,
    Kind kind
) {
    public enum Kind { SWING_MAIN, SWING_OFF, HURT, CRITICAL }

    public ReplayVisualEvent {
        if (tick < 0 || attempt < 1) {
            throw new IllegalArgumentException("Invalid visual event time or attempt.");
        }
        Objects.requireNonNull(worldId);
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(kind);
    }
}
