package dev.instigatehardcore.replay;

import java.util.List;
import java.util.Objects;

/**
 * Phase 9B snapshot: a fully immutable scene, its final death moment,
 * and in-window damage events. Does not alter gameplay or start playback.
 */
public record FrozenDeathReplay(
    ReplayClip clip,
    ReplayDeathMoment death,
    List<ReplayCombatEvent> combatEvents,
    List<ReplayVisualEvent> visualEvents
) {
    /** Keep existing 9B/9C tests and callers source-compatible. */
    public FrozenDeathReplay(
        ReplayClip clip, ReplayDeathMoment death, List<ReplayCombatEvent> combatEvents
    ) {
        this(clip, death, combatEvents, List.of());
    }

    public FrozenDeathReplay {
        Objects.requireNonNull(clip);
        Objects.requireNonNull(death);
        combatEvents = List.copyOf(Objects.requireNonNull(combatEvents));
        visualEvents = List.copyOf(Objects.requireNonNull(visualEvents));

        if (clip.attempt() != death.attempt()
            || !clip.victimId().equals(death.victimId())
            || !clip.worldId().equals(death.worldId())
            || death.tick() < clip.frames().getLast().tick()) {
            throw new IllegalArgumentException("Clip and death metadata must match.");
        }

        long startTick = clip.frames().getFirst().tick();
        for (ReplayCombatEvent event : combatEvents) {
            if (event.attempt() != clip.attempt()
                || !event.worldId().equals(clip.worldId())
                || event.tick() < startTick || event.tick() > death.tick()) {
                throw new IllegalArgumentException("Combat event lies outside clip timeline.");
            }
        }
        for (ReplayVisualEvent event : visualEvents) {
            if (event.attempt() != clip.attempt()
                || !event.worldId().equals(clip.worldId())
                || event.tick() < startTick || event.tick() > death.tick()) {
                throw new IllegalArgumentException("Visual event lies outside clip timeline.");
            }
        }
    }
}
