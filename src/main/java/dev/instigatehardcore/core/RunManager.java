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
     * Marks the current run as active.
     *
     * @return true if the run was started, false if it was already
     *         in another state.
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
     * Attempts to end the current run.
     *
     * Only the first caller while ACTIVE succeeds.
     *
     * @return true if this call ended the run.
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
     * Marks the run as being reset.
     *
     * @return true if the state transitioned from ENDING to RESETTING.
     */
    public synchronized boolean beginResetting() {
        if (state != RunState.ENDING) {
            return false;
        }

        state = RunState.RESETTING;

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
     * Returns true once the run has left the ACTIVE state
     * because of a run-ending death.
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
     * Returns elapsed play time for the current run.
     *
     * While ACTIVE:
     *     startedAt -> now
     *
     * Once the run ends:
     *     startedAt -> endedAt
     *
     * This prevents the timer from continuing during the reset countdown.
     */
    public synchronized Duration getElapsedTime() {
        if (startedAt == null) {
            return Duration.ZERO;
        }

        Instant end = endedAt != null
            ? endedAt
            : Instant.now();

        Duration duration = Duration.between(
            startedAt,
            end
        );

        /*
         * Defensive safeguard against clock adjustments producing
         * a negative duration.
         */
        if (duration.isNegative()) {
            return Duration.ZERO;
        }

        return duration;
    }
}