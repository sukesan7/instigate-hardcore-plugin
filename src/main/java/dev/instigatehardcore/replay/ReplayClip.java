package dev.instigatehardcore.replay;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A frozen clip, detached from the rolling recorder's mutable buffers. */
public record ReplayClip(
    int attempt,
    UUID victimId,
    UUID worldId,
    List<ReplayFrame> frames
) {
    public ReplayClip {
        if (attempt < 1) {
            throw new IllegalArgumentException("Attempt must be positive.");
        }
        Objects.requireNonNull(victimId);
        Objects.requireNonNull(worldId);
        frames = List.copyOf(Objects.requireNonNull(frames));
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("Replay clip cannot be empty.");
        }
        for (ReplayFrame frame : frames) {
            if (frame.attempt() != attempt
                || !frame.subjectId().equals(victimId)
                || !frame.worldId().equals(worldId)) {
                throw new IllegalArgumentException("Replay frames must share the clip's attempt, subject and world.");
            }
        }
    }
}
