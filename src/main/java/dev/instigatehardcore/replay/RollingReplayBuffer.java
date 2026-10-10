package dev.instigatehardcore.replay;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Bounded, in-memory frames for one subject. On dimension or attempt
 * changes the buffer starts over: a single replay never mixes worlds.
 */
public final class RollingReplayBuffer {

    private final int maximumFrames;
    private final Deque<ReplayFrame> frames = new ArrayDeque<>();

    public RollingReplayBuffer(int maximumFrames) {
        if (maximumFrames < 1) {
            throw new IllegalArgumentException("Maximum frames must be positive.");
        }
        this.maximumFrames = maximumFrames;
    }

    public void append(ReplayFrame frame) {
        Objects.requireNonNull(frame);
        ReplayFrame last = frames.peekLast();
        if (last != null && (
            last.attempt() != frame.attempt()
                || !last.worldId().equals(frame.worldId())
                || !last.subjectId().equals(frame.subjectId())
                || frame.tick() <= last.tick()
        )) {
            frames.clear();
        }
        frames.addLast(frame);
        while (frames.size() > maximumFrames) {
            frames.removeFirst();
        }
    }

    public List<ReplayFrame> snapshot() {
        return List.copyOf(frames);
    }

    public int size() {
        return frames.size();
    }

    public void clear() {
        frames.clear();
    }
}
