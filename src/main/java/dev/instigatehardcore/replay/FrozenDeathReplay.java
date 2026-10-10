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
    List<ReplayCombatEvent> combatEvents
) {
    public FrozenDeathReplay {
        Objects.requireNonNull(clip);
        Objects.requireNonNull(death);
        combatEvents = List.copyOf(Objects.requireNonNull(combatEvents));

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
    }
}
