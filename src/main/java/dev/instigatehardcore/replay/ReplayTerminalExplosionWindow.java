package dev.instigatehardcore.replay;

import java.util.Objects;
import java.util.UUID;

/** Only allows late death-tick explosion blocks near the fatal death. */
public record ReplayTerminalExplosionWindow(
    int attempt, UUID worldId, int serverTick,
    double x, double y, double z, int radius
) {
    public ReplayTerminalExplosionWindow {
        if (attempt < 1 || radius < 1 || !Double.isFinite(x)
            || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Invalid terminal explosion window");
        }
        Objects.requireNonNull(worldId);
    }

    public boolean accepts(int eventAttempt, UUID eventWorld, int eventTick,
                           double blockX, double blockY, double blockZ) {
        long delta = (long) eventTick - serverTick;
        double dx = blockX - x;
        double dy = blockY - y;
        double dz = blockZ - z;
        return eventAttempt == attempt && worldId.equals(eventWorld)
            && delta >= 0 && delta <= 1
            && dx * dx + dy * dy + dz * dz <= (double) radius * radius;
    }
}
