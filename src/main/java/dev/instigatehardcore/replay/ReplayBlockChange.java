package dev.instigatehardcore.replay;

import java.util.Objects;
import java.util.UUID;

/** One observed block transition in an active attempt and dimension. */
public record ReplayBlockChange(
    long tick, int attempt, UUID worldId, ReplayBlockPosition position,
    String beforeData, String afterData
) {
    public ReplayBlockChange {
        if (tick < 0 || attempt < 1) {
            throw new IllegalArgumentException("Invalid block history time/attempt");
        }
        Objects.requireNonNull(worldId);
        Objects.requireNonNull(position);
        if (Objects.requireNonNull(beforeData).isBlank()
            || Objects.requireNonNull(afterData).isBlank()) {
            throw new IllegalArgumentException("Block data strings cannot be blank");
        }
    }
}
