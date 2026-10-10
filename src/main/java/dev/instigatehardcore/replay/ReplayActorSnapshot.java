package dev.instigatehardcore.replay;

import java.util.Objects;
import java.util.UUID;

/** One actor's appearance and movement at one sampled instant. */
public record ReplayActorSnapshot(
    UUID entityId,
    String entityType,
    String name,
    double x,
    double y,
    double z,
    float yaw,
    float pitch,
    boolean sneaking,
    boolean gliding,
    boolean burning,
    double health,
    ReplayEquipment equipment
) {
    public ReplayActorSnapshot {
        Objects.requireNonNull(entityId);
        Objects.requireNonNull(entityType);
        Objects.requireNonNull(name);
        Objects.requireNonNull(equipment);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Actor coordinates must be finite.");
        }
    }
}
