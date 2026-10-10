package dev.instigatehardcore.replay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Maps real sampled ticks onto an immutable replay-tick event timeline. */
public final class ReplayVisualEventTimeline {
    private final Map<Integer, List<ReplayVisualEvent>> byTick;

    public ReplayVisualEventTimeline(ReplayActorScene scene, List<ReplayVisualEvent> events) {
        Objects.requireNonNull(scene);
        Objects.requireNonNull(events);
        Map<Integer, List<ReplayVisualEvent>> mutable = new HashMap<>();
        for (ReplayVisualEvent event : events) {
            if (event.attempt() != scene.attempt() || !event.worldId().equals(scene.worldId())) {
                continue;
            }
            int at = scene.playbackTickOf(event.tick());
            if (at < 0) {
                continue;
            }
            // 9E removes ghosts immediately after its terminal playback tick.
            // Show end-of-clip impacts a few ticks early so Java clients have
            // time to render the hurt tint and critical particles before removal.
            int visualTick = scene.durationTicks() >= 10
                ? Math.min(at, scene.durationTicks() - 5)
                : at;
            mutable.computeIfAbsent(visualTick, ignored -> new ArrayList<>()).add(event);
        }
        Map<Integer, List<ReplayVisualEvent>> frozen = new HashMap<>();
        mutable.forEach((tick, list) -> frozen.put(tick, List.copyOf(list)));
        byTick = Map.copyOf(frozen);
    }

    public List<ReplayVisualEvent> at(int tick) {
        return byTick.getOrDefault(tick, List.of());
    }
}
