package dev.instigatehardcore.core;

import java.time.Duration;
import java.time.Instant;

public final class RunManager {

    private RunState state;
    private Instant startedAt;
    private Instant endedAt;

    public RunManager() {
        this.state = RunState.STARTING;
    }

    /**
     * Starts the initial hardcore run.
     *
     * STARTING -> ACTIVE
     */
    public synchronized boolean startRun() {
        if (state != RunState.STARTING) {
            return false;
        }

        startedAt = Instant.now();
        endedAt = null;

        state = RunState.ACTIVE;

        return true;
    }

    /**
     * Ends the currently active run.
     *
     * Only the first caller while ACTIVE succeeds.
     *
     * ACTIVE -> ENDING
     */
    public synchronized boolean beginEnding() {
        if (state != RunState.ACTIVE) {
            return false;
        }

        endedAt = Instant.now();

        state = RunState.ENDING;

        return true;
    }

    /**
     * Begins the world-transition phase.
     *
     * ENDING -> RESETTING
     */
    public synchronized boolean beginResetting() {
        if (state != RunState.ENDING) {
            return false;
        }

        state = RunState.RESETTING;

        return true;
    }

    /**
     * Starts the next hardcore attempt after a successful
     * seamless world rotation.
     *
     * RESETTING -> ACTIVE
     */
    public synchronized boolean beginNextRun() {
        if (state != RunState.RESETTING) {
            return false;
        }

        startedAt = Instant.now();
        endedAt = null;

        state = RunState.ACTIVE;

        return true;
    }

    public synchronized RunState getState() {
        return state;
    }

    public synchronized boolean isActive() {
        return state == RunState.ACTIVE;
    }

    public synchronized boolean isEnding() {
        return state == RunState.ENDING;
    }

    public synchronized boolean isResetting() {
        return state == RunState.RESETTING;
    }

    /**
     * Returns true once the current attempt has ended.
     */
    public synchronized boolean hasEnded() {
        return state == RunState.ENDING
            || state == RunState.RESETTING;
    }

    public synchronized Instant getStartedAt() {
        return startedAt;
    }

    public synchronized Instant getEndedAt() {
        return endedAt;
    }

    /**
     * Returns elapsed gameplay time.
     *
     * While ACTIVE:
     *     startedAt -> now
     *
     * Once the attempt ends:
     *     startedAt -> endedAt
     *
     * This freezes the timer during the countdown and world
     * transition.
     */
    public synchronized Duration getElapsedTime() {
        if (startedAt == null) {
            return Duration.ZERO;
        }

        Instant end =
            endedAt != null
                ? endedAt
                : Instant.now();

        Duration duration =
            Duration.between(
                startedAt,
                end
            );

        if (duration.isNegative()) {
            return Duration.ZERO;
        }

        return duration;
    }
}