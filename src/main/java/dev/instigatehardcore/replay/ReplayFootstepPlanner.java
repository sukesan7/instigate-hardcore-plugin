package dev.instigatehardcore.replay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Deterministic, bounded sampled footsteps. No Bukkit access; never uses
 * current-world movement. This approximates steps between replay keyframes.
 */
public final class ReplayFootstepPlanner {
    private record History(double startX, double startZ, int lastStepTick) { }
    private final int maxStepsPerTick;
    private final Map<UUID, History> history = new HashMap<>();
    private int previousTick = -1;

    public ReplayFootstepPlanner(int maxStepsPerTick) {
        if (maxStepsPerTick < 1 || maxStepsPerTick > 64)
            throw new IllegalArgumentException("Invalid footstep budget.");
        this.maxStepsPerTick = maxStepsPerTick;
    }

    public List<ReplayActorPose> advance(int tick, Map<UUID, ReplayActorPose> actors) {
        Objects.requireNonNull(actors);
        if (tick <= previousTick) throw new IllegalArgumentException("Replay time not increasing.");
        previousTick = tick;
        history.keySet().retainAll(actors.keySet());
        List<ReplayActorPose> steps = new ArrayList<>();
        for (ReplayActorPose pose : actors.values()) {
            if (!"PLAYER".equalsIgnoreCase(pose.entityType())) continue;
            History old = history.get(pose.id());
            if (!pose.onGround() || pose.gliding() || !validGround(pose.groundMaterial())) {
                history.put(pose.id(), new History(pose.x(), pose.z(), tick));
                continue;
            }
            if (old == null) {
                history.put(pose.id(), new History(pose.x(), pose.z(), tick));
                continue;
            }
            double dx = pose.x() - old.startX();
            double dz = pose.z() - old.startZ();
            double distanceSq = dx * dx + dz * dz;
            int interval = pose.sneaking() ? 7 : pose.sprinting() ? 4 : 5;
            double minDistance = pose.sneaking() ? 0.24 : 0.45;
            if (tick - old.lastStepTick() >= interval
                && distanceSq >= minDistance * minDistance) {
                if (steps.size() < maxStepsPerTick) steps.add(pose);
                history.put(pose.id(), new History(pose.x(), pose.z(), tick));
            }
        }
        return List.copyOf(steps);
    }

    private static boolean validGround(String m) {
        return !m.equalsIgnoreCase("AIR") && !m.equalsIgnoreCase("CAVE_AIR")
            && !m.equalsIgnoreCase("VOID_AIR")
            && !m.contains("WATER") && !m.contains("LAVA");
    }
}
