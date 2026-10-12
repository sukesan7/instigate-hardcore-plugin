package dev.instigatehardcore.replay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure replay timeline for block changes. The first 'before' state for each
 * position reconstructs that position at the beginning of the recording.
 * Later events apply in their original capture order, including same-tick
 * place/break operations.
 */
public final class ReplayTerrainTimeline {
    private final Map<ReplayBlockPosition, String> initial;
    private final Map<Integer, List<ReplayBlockChange>> changesByTick;

    public ReplayTerrainTimeline(ReplayActorScene scene, List<ReplayBlockChange> events) {
        Objects.requireNonNull(scene);
        Objects.requireNonNull(events);
        LinkedHashMap<ReplayBlockPosition, String> initialState = new LinkedHashMap<>();
        Map<Integer, List<ReplayBlockChange>> mutable = new LinkedHashMap<>();
        for (ReplayBlockChange change : events) {
            if (change.attempt() != scene.attempt()
                || !change.worldId().equals(scene.worldId())) {
                continue;
            }
            int at = scene.playbackTickOf(change.tick());
            if (at < 0) continue;
            initialState.putIfAbsent(change.position(), change.beforeData());
            // Changes after the first sampled frame must not overwrite the
            // reconstructed starting state in the same tick we send it.
            // Terminal destruction plays early enough to be seen before reset.
            int renderTick = Math.max(1, at);
            if (scene.durationTicks() >= 10) {
                renderTick = Math.min(renderTick, scene.durationTicks() - 5);
            } else {
                renderTick = Math.min(renderTick, scene.durationTicks());
            }
            mutable.computeIfAbsent(renderTick, ignored -> new ArrayList<>()).add(change);
        }
        this.initial = Collections.unmodifiableMap(new LinkedHashMap<>(initialState));
        Map<Integer, List<ReplayBlockChange>> frozen = new LinkedHashMap<>();
        mutable.forEach((tick, changes) -> frozen.put(tick, List.copyOf(changes)));
        this.changesByTick = Map.copyOf(frozen);
    }

    public Map<ReplayBlockPosition, String> initialStates() {
        return initial;
    }

    public List<ReplayBlockChange> changesAt(int tick) {
        return changesByTick.getOrDefault(tick, List.of());
    }
}
