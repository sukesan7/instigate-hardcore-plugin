package dev.instigatehardcore.world;

public record WorldRotationState(
    int activeAttempt,
    long activeSeed,
    int standbyAttempt,
    long standbySeed
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
    }
}