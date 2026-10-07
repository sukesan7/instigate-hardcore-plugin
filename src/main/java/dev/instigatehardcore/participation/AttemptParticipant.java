package dev.instigatehardcore.participation;

import java.util.Objects;
import java.util.UUID;

public record AttemptParticipant(
    UUID uuid,
    String name,
    long firstJoinedAt
) {

    public AttemptParticipant {
        Objects.requireNonNull(uuid);
        Objects.requireNonNull(name);

        if (name.isBlank()) {
            throw new IllegalArgumentException(
                "Participant name cannot be blank."
            );
        }

        if (firstJoinedAt < 0) {
            throw new IllegalArgumentException(
                "Participant join time cannot be negative."
            );
        }
    }
}