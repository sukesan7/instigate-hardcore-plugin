package dev.instigatehardcore.world;

import java.util.Objects;

public record WorldRotationState(
    int activeAttempt,
    long activeSeed,
    int standbyAttempt,
    long standbySeed,
    WorldRotationPhase phase
) {

    public WorldRotationState {
        if (activeAttempt < 1) {
            throw new IllegalArgumentException(
                "Active attempt must be at least one."
            );
        }

        if (standbyAttempt != activeAttempt + 1) {
            throw new IllegalArgumentException(
                "Standby attempt must immediately follow active attempt."
            );
        }

        Objects.requireNonNull(
            phase,
            "World rotation phase cannot be null."
        );
    }

    /**
     * Compatibility constructor.
     *
     * Existing code/tests creating a four-field state are
     * interpreted as a normal stable pipeline.
     */
    public WorldRotationState(
        int activeAttempt,
        long activeSeed,
        int standbyAttempt,
        long standbySeed
    ) {
        this(
            activeAttempt,
            activeSeed,
            standbyAttempt,
            standbySeed,
            WorldRotationPhase.STABLE
        );
    }
}