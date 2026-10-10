package dev.instigatehardcore.replay;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Bounded per-subject visual event history. Main-thread only. */
public final class RollingReplayVisualEventBuffer {
    private final int maxEvents;
    private final long windowTicks;
    private final Deque<ReplayVisualEvent> events = new ArrayDeque<>();

    public RollingReplayVisualEventBuffer(int maxEvents, long windowTicks) {
        if (maxEvents < 1 || windowTicks < 1) {
            throw new IllegalArgumentException("Invalid visual event buffer limits.");
        }
        this.maxEvents = maxEvents;
        this.windowTicks = windowTicks;
    }

    public void append(ReplayVisualEvent event) {
        Objects.requireNonNull(event);
        ReplayVisualEvent last = events.peekLast();
        if (last != null && (last.attempt() != event.attempt()
            || !last.worldId().equals(event.worldId())
            || event.tick() < last.tick())) {
            events.clear();
        }
        events.addLast(event);
        while (!events.isEmpty() && (events.size() > maxEvents
            || events.peekFirst().tick() < event.tick() - windowTicks)) {
            events.removeFirst();
        }
    }

    public List<ReplayVisualEvent> snapshot(
        int attempt, UUID worldId, long fromTick, long throughTick
    ) {
        Objects.requireNonNull(worldId);
        if (fromTick > throughTick) {
            throw new IllegalArgumentException("Invalid visual event range.");
        }
        List<ReplayVisualEvent> selected = new ArrayList<>();
        for (ReplayVisualEvent event : events) {
            if (event.attempt() == attempt && event.worldId().equals(worldId)
                && event.tick() >= fromTick && event.tick() <= throughTick) {
                selected.add(event);
            }
        }
        return List.copyOf(selected);
    }

    public int size() { return events.size(); }
    public void clear() { events.clear(); }
}
