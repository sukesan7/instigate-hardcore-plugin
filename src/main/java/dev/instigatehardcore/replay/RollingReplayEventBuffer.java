package dev.instigatehardcore.replay;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Bounded event timeline for one player's local recorded scene. */
public final class RollingReplayEventBuffer {
    private final int maxEvents;
    private final long windowTicks;
    private final Deque<ReplayCombatEvent> events = new ArrayDeque<>();

    public RollingReplayEventBuffer(int maxEvents, long windowTicks) {
        if (maxEvents < 1 || windowTicks < 1) {
            throw new IllegalArgumentException("Invalid event limits.");
        }
        this.maxEvents = maxEvents;
        this.windowTicks = windowTicks;
    }

    public void append(ReplayCombatEvent event) {
        Objects.requireNonNull(event);
        ReplayCombatEvent last = events.peekLast();
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

    public List<ReplayCombatEvent> snapshot(
        int attempt, UUID worldId, long startTick, long endTick
    ) {
        Objects.requireNonNull(worldId);
        if (startTick > endTick) {
            throw new IllegalArgumentException("Invalid event time range.");
        }
        List<ReplayCombatEvent> result = new ArrayList<>();
        for (ReplayCombatEvent event : events) {
            if (event.attempt() == attempt && event.worldId().equals(worldId)
                && event.tick() >= startTick && event.tick() <= endTick) {
                result.add(event);
            }
        }
        return List.copyOf(result);
    }

    public int size() {
        return events.size();
    }

    public void clear() {
        events.clear();
    }
}
