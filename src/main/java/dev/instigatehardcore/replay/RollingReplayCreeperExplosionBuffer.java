package dev.instigatehardcore.replay;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Bounded time window of observed explosions, scoped per recorded spectator. */
public final class RollingReplayCreeperExplosionBuffer {
    private final int capacity;
    private final long windowTicks;
    private final ArrayDeque<ReplayCreeperExplosionEvent> events = new ArrayDeque<>();

    public RollingReplayCreeperExplosionBuffer(int capacity, long windowTicks) {
        if (capacity < 1 || windowTicks < 1) {
            throw new IllegalArgumentException("Invalid replay event limits.");
        }
        this.capacity = capacity;
        this.windowTicks = windowTicks;
    }

    public void append(ReplayCreeperExplosionEvent event) {
        if (!events.isEmpty() && event.tick() < events.getLast().tick()) {
            throw new IllegalArgumentException("Explosion event ticks must not decrease.");
        }
        events.addLast(event);
        while (events.size() > capacity || (!events.isEmpty()
            && events.getFirst().tick() < event.tick() - windowTicks)) {
            events.removeFirst();
        }
    }

    public List<ReplayCreeperExplosionEvent> snapshot(
        int attempt, java.util.UUID worldId, long startTick, long endTick
    ) {
        ArrayList<ReplayCreeperExplosionEvent> out = new ArrayList<>();
        for (ReplayCreeperExplosionEvent event : events) {
            if (event.attempt() == attempt && event.worldId().equals(worldId)
                && event.tick() >= startTick && event.tick() <= endTick) {
                out.add(event);
            }
        }
        return List.copyOf(out);
    }
}
