package dev.instigatehardcore.replay;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Actor IDs recorded anywhere in a frozen clip, in first-seen order.
 *
 * The shared replay computes this once and reuses the immutable result for
 * every spectator.  No world lookup or Bukkit API is used here.
 */
public final class ReplayViewerActorIndex {
    private ReplayViewerActorIndex() { }

    public static Set<UUID> fromClip(ReplayClip clip) {
        Objects.requireNonNull(clip, "clip");
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        for (ReplayFrame frame : clip.frames()) {
            for (ReplayActorSnapshot actor : frame.actors()) {
                ids.add(actor.entityId());
            }
        }
        return Collections.unmodifiableSet(ids);
    }
}
