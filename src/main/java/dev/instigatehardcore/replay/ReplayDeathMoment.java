package dev.instigatehardcore.replay;

import java.util.Objects;
import java.util.UUID;

/** Exact location and metadata captured inside PlayerDeathEvent. */
public record ReplayDeathMoment(
    int attempt,
    UUID victimId,
    UUID worldId,
    long tick,
    long epochMillis,
    double x,
    double y,
    double z,
    float yaw,
    float pitch,
    String deathMessage,
    String deathCause
) {
    public ReplayDeathMoment {
        if (attempt < 1 || tick < 0 || epochMillis < 0) {
            throw new IllegalArgumentException("Invalid death replay timing.");
        }
        Objects.requireNonNull(victimId);
        Objects.requireNonNull(worldId);
        Objects.requireNonNull(deathMessage);
        Objects.requireNonNull(deathCause);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
            || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Invalid death replay location.");
        }
    }
}
