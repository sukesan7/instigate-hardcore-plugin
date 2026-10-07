package dev.instigatehardcore.core;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class RunManager {

    private RunState state;
    private Instant startedAt;

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

        state = RunState.ENDING;
        return true;
    }

    /**
     * Marks the run as being reset.
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

    public synchronized Instant getStartedAt() {
        return startedAt;
    }

    public synchronized boolean hasEnded() {
        return state == RunState.ENDING || state == RunState.RESETTING;
    }

    public synchronized Duration getElapsedTime() {
        if (startedAt == null) {
            return Duration.ZERO;
        }

        return Duration.between(startedAt, Instant.now());
    }
}