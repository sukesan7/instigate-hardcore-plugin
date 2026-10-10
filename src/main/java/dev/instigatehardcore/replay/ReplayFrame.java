package dev.instigatehardcore.replay;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** One frame, always belonging to exactly one attempt and dimension. */
public record ReplayFrame(
    long tick,
    int attempt,
    UUID worldId,
    UUID subjectId,
    List<ReplayActorSnapshot> actors
) {
    public ReplayFrame {
        if (attempt < 1) {
            throw new IllegalArgumentException("Attempt must be positive.");
        }
        Objects.requireNonNull(worldId);
        Objects.requireNonNull(subjectId);
        actors = List.copyOf(Objects.requireNonNull(actors));
    }
}
