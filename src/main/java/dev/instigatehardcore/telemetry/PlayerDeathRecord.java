package dev.instigatehardcore.telemetry;

import java.util.Objects;

public record PlayerDeathRecord(
    int attempt,
    long timestamp,
    String cause,
    String message
) {

    public PlayerDeathRecord {
        if (attempt < 1) {
            throw new IllegalArgumentException(
                "Attempt number must be at least one."
            );
        }

        if (timestamp < 0) {
            throw new IllegalArgumentException(
                "Death timestamp cannot be negative."
            );
        }

        Objects.requireNonNull(
            cause
        );

        Objects.requireNonNull(
            message
        );

        if (cause.isBlank()) {
            throw new IllegalArgumentException(
                "Death cause cannot be blank."
            );
        }
    }
}