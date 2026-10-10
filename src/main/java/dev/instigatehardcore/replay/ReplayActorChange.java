package dev.instigatehardcore.replay;

import java.util.Objects;
import java.util.UUID;

/** One packet-renderer instruction. REMOVE deliberately has no pose. */
public record ReplayActorChange(Kind kind, UUID actorId, ReplayActorPose pose) {
    public enum Kind { SPAWN, UPDATE, REMOVE }

    public ReplayActorChange {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(actorId);
        if (kind == Kind.REMOVE && pose != null) {
            throw new IllegalArgumentException("REMOVE must not include a pose.");
        }
        if (kind != Kind.REMOVE && (pose == null || !pose.id().equals(actorId))) {
            throw new IllegalArgumentException("SPAWN/UPDATE requires matching pose.");
        }
    }

    public static ReplayActorChange spawn(ReplayActorPose pose) {
        return new ReplayActorChange(Kind.SPAWN, pose.id(), pose);
    }

    public static ReplayActorChange update(ReplayActorPose pose) {
        return new ReplayActorChange(Kind.UPDATE, pose.id(), pose);
    }

    public static ReplayActorChange remove(UUID actorId) {
        return new ReplayActorChange(Kind.REMOVE, actorId, null);
    }
}
