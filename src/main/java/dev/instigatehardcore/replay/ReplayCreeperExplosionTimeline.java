package dev.instigatehardcore.replay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure mapping of real explosion ticks to replay ticks; no ghost entity dependency. */
public final class ReplayCreeperExplosionTimeline {
    private final Map<Integer, List<ReplayCreeperExplosionEvent>> byTick;

    public ReplayCreeperExplosionTimeline(
        ReplayActorScene scene, List<ReplayCreeperExplosionEvent> events
    ) {
        Objects.requireNonNull(scene);
        Objects.requireNonNull(events);
        Map<Integer, List<ReplayCreeperExplosionEvent>> mutable = new HashMap<>();
        for (ReplayCreeperExplosionEvent event : events) {
            if (event.attempt() != scene.attempt()
                || !event.worldId().equals(scene.worldId())) continue;
            int at = scene.playbackTickOf(event.tick());
            if (at < 0) continue;
            // Guarantee a visible terminal blast before the 9E ghost cleanup.
            int renderTick = scene.durationTicks() >= 10
                ? Math.min(at, scene.durationTicks() - 5) : at;
            mutable.computeIfAbsent(renderTick, ignored -> new ArrayList<>()).add(event);
        }
        Map<Integer, List<ReplayCreeperExplosionEvent>> frozen = new HashMap<>();
        mutable.forEach((tick, entries) -> frozen.put(tick, List.copyOf(entries)));
        byTick = Map.copyOf(frozen);
    }

    public List<ReplayCreeperExplosionEvent> at(int tick) {
        return byTick.getOrDefault(tick, List.of());
    }
}
