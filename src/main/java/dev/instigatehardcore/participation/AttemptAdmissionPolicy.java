package dev.instigatehardcore.participation;

public final class AttemptAdmissionPolicy {

    private final boolean allowLateJoiners;

    public AttemptAdmissionPolicy(
        boolean allowLateJoiners
    ) {
        this.allowLateJoiners =
            allowLateJoiners;
    }

    /**
     * Determines whether a player may enter the current ACTIVE
     * attempt.
     *
     * Rules:
     *
     * 1. Existing participants may always rejoin.
     * 2. New participants may join when late joining is enabled.
     * 3. When late joining is disabled, the first participant in
     *    a completely empty attempt is still admitted so the
     *    server cannot deadlock.
     * 4. Otherwise, the new player must wait for the next attempt.
     */
    public boolean mayEnter(
        boolean existingParticipant,
        int participantCount
    ) {
        if (participantCount < 0) {
            throw new IllegalArgumentException(
                "Participant count cannot be negative."
            );
        }

        if (existingParticipant) {
            return true;
        }

        if (allowLateJoiners) {
            return true;
        }

        return participantCount == 0;
    }

    public boolean allowsLateJoiners() {
        return allowLateJoiners;
    }
}