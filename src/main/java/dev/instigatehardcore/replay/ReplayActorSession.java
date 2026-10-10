package dev.instigatehardcore.replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Builds per-viewer SPAWN/UPDATE/REMOVE instructions from a replay scene.
 *
 * Call advanceTo() on the main tick thread with monotonically increasing
 * playback ticks. Keep one session PER VIEWER: packet actor identifiers and
 * visibility are not shared between clients. close() always removes actors.
 */
public final class ReplayActorSession implements AutoCloseable {
    private final ReplayActorScene scene;
    private final Map<UUID, ReplayActorPose> visible = new LinkedHashMap<>();
    private int previousTick = -1;
    private boolean closed;

    public ReplayActorSession(ReplayActorScene scene) {
        this.scene = Objects.requireNonNull(scene);
    }

    public List<ReplayActorChange> advanceTo(int tick) {
        if (closed) {
            throw new IllegalStateException("Replay actor session is closed.");
        }
        if (tick < previousTick) {
            throw new IllegalArgumentException("Replay ticks must be monotonic.");
        }
        Map<UUID, ReplayActorPose> next = scene.at(tick);
        List<ReplayActorChange> changes = new ArrayList<>();

        for (UUID id : visible.keySet()) {
            ReplayActorPose oldPose = visible.get(id);
            ReplayActorPose nextPose = next.get(id);
            if (nextPose == null || !oldPose.entityType().equals(nextPose.entityType())) {
                changes.add(ReplayActorChange.remove(id));
            }
        }
        for (ReplayActorPose pose : next.values()) {
            ReplayActorPose prior = visible.get(pose.id());
            if (prior == null || !prior.entityType().equals(pose.entityType())) {
                changes.add(ReplayActorChange.spawn(pose));
            } else if (!prior.equals(pose)) {
                changes.add(ReplayActorChange.update(pose));
            }
        }
        visible.clear();
        visible.putAll(next);
        previousTick = tick;
        return List.copyOf(changes);
    }

    /** Snapshot of all REMOVE instructions needed by packet renderer at end. */
    public List<ReplayActorChange> clearActors() {
        List<ReplayActorChange> changes = new ArrayList<>();
        for (UUID id : visible.keySet()) {
            changes.add(ReplayActorChange.remove(id));
        }
        visible.clear();
        return List.copyOf(changes);
    }

    @Override
    public void close() {
        // The caller must send clearActors() BEFORE close() to remove fake
        // packet entities. We keep close() side-effect-free towards clients.
        visible.clear();
        closed = true;
    }

    public boolean isClosed() {
        return closed;
    }
}
