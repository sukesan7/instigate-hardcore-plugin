package dev.instigatehardcore.replay;

/**
 * Pure timing policy for a fixed-length run-ending sequence.
 * The requested design is ten seconds in total: seven of replay,
 * followed by three of ordinary countdown.
 */
public record ReplayTiming(int totalSeconds, int replaySeconds) {

    public ReplayTiming {
        if (totalSeconds < 2 || totalSeconds > 600
            || replaySeconds < 1 || replaySeconds >= totalSeconds) {
            throw new IllegalArgumentException(
                "Replay requires at least one second for replay and buffer."
            );
        }
    }

    public int replayTicks() {
        return replaySeconds * 20;
    }

    public int bufferSeconds() {
        return totalSeconds - replaySeconds;
    }

    /**
     * When playback fails, spend only the remaining portion of the
     * originally planned sequence waiting, but always retain the buffer.
     */
    public int remainingOnFailure(int playbackTicksElapsed) {
        if (playbackTicksElapsed < 0) {
            throw new IllegalArgumentException("Playback elapsed ticks cannot be negative.");
        }
        int elapsedSeconds = Math.min(
            replaySeconds,
            (playbackTicksElapsed + 19) / 20
        );
        return Math.max(bufferSeconds(), totalSeconds - elapsedSeconds);
    }
}
