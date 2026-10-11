package dev.instigatehardcore.replay;

import java.util.Objects;
import java.util.UUID;

/** Recorded explosion position is independent of whether the creeper ghost still exists. */
public record ReplayCreeperExplosionEvent(
    long tick, int attempt, UUID worldId, UUID creeperId,
    double x, double y, double z, boolean powered
) {
    public ReplayCreeperExplosionEvent {
        if (tick < 0 || attempt < 1) {
            throw new IllegalArgumentException("Invalid creeper explosion time or attempt.");
        }
        Objects.requireNonNull(worldId);
        Objects.requireNonNull(creeperId);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Explosion coordinates must be finite.");
        }
    }
}
