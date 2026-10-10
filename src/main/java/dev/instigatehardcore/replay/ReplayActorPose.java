package dev.instigatehardcore.replay;

import java.util.Objects;
import java.util.UUID;

/** Immutable pose and visible state of one client-side replay actor. */
public record ReplayActorPose(
    UUID id,
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
    public ReplayActorPose {
        Objects.requireNonNull(id);
        Objects.requireNonNull(entityType);
        Objects.requireNonNull(name);
        Objects.requireNonNull(equipment);
        if (entityType.isBlank()) {
            throw new IllegalArgumentException("Entity type must not be blank.");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
            || !Float.isFinite(yaw) || !Float.isFinite(pitch)
            || !Double.isFinite(health)) {
            throw new IllegalArgumentException("Non-finite actor pose value.");
        }
    }

    public static ReplayActorPose from(ReplayActorSnapshot snapshot) {
        return new ReplayActorPose(
            snapshot.entityId(), snapshot.entityType(), snapshot.name(),
            snapshot.x(), snapshot.y(), snapshot.z(), snapshot.yaw(),
            snapshot.pitch(), snapshot.sneaking(), snapshot.gliding(),
            snapshot.burning(), snapshot.health(), snapshot.equipment()
        );
    }

    /** Angles follow the shortest arc; appearance/equipment switch on the next keyframe. */
    public static ReplayActorPose interpolate(
        ReplayActorSnapshot previous,
        ReplayActorSnapshot next,
        double fraction
    ) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(next);
        if (!previous.entityId().equals(next.entityId())
            || !previous.entityType().equals(next.entityType())) {
            throw new IllegalArgumentException("Cannot interpolate different actor identities or types.");
        }
        if (!Double.isFinite(fraction) || fraction < 0.0 || fraction > 1.0) {
            throw new IllegalArgumentException("Interpolation fraction must be between zero and one.");
        }
        if (fraction == 1.0) {
            return from(next);
        }
        return new ReplayActorPose(
            previous.entityId(), previous.entityType(), previous.name(),
            mix(previous.x(), next.x(), fraction),
            mix(previous.y(), next.y(), fraction),
            mix(previous.z(), next.z(), fraction),
            mixAngle(previous.yaw(), next.yaw(), fraction),
            mixAngle(previous.pitch(), next.pitch(), fraction),
            previous.sneaking(), previous.gliding(), previous.burning(),
            mix(previous.health(), next.health(), fraction),
            previous.equipment()
        );
    }

    private static double mix(double a, double b, double alpha) {
        return a + (b - a) * alpha;
    }

    private static float mixAngle(float a, float b, double alpha) {
        double delta = ((b - a + 540.0) % 360.0) - 180.0;
        return (float) (a + delta * alpha);
    }
}
