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
    ReplayEquipment equipment,
    boolean sprinting,
    boolean onGround,
    String groundMaterial
) {
    /** Source-compatible constructor used by earlier phases and tests. */
    public ReplayActorSnapshot(
        UUID entityId, String entityType, String name,
        double x, double y, double z, float yaw, float pitch,
        boolean sneaking, boolean gliding, boolean burning,
        double health, ReplayEquipment equipment
    ) {
        this(entityId, entityType, name, x, y, z, yaw, pitch,
            sneaking, gliding, burning, health, equipment,
            false, false, "AIR");
    }

    public ReplayActorSnapshot {
        Objects.requireNonNull(entityId);
        Objects.requireNonNull(entityType);
        Objects.requireNonNull(name);
        Objects.requireNonNull(equipment);
        Objects.requireNonNull(groundMaterial);
        if (groundMaterial.isBlank()) {
            throw new IllegalArgumentException("Ground material must not be blank.");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Actor coordinates must be finite.");
        }
    }
}
