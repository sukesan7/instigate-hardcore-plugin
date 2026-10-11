package dev.instigatehardcore.replay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Per-viewer, deterministic movement FX state machine. Pure Java: no world
 * reads, no fake Bukkit entities, and no particle broadcasts.
 *
 * Only PLAYER poses with recorded ground material can emit effects. Sprint
 * dust requires actual horizontal displacement; landing dust needs at least
 * three observed airborne ticks. Total particles are capped per viewer/tick.
 */
public final class ReplayMovementEffectPlanner {
    private static final int SPRINT_INTERVAL_TICKS = 4;
    private static final double MIN_HORIZONTAL_DISTANCE_SQUARED = 0.045 * 0.045;
    private static final int SPRINT_PARTICLES = 3;
    private static final int LANDING_PARTICLES = 7;

    private record History(ReplayActorPose pose, int airborneTicks) { }
    private final Map<UUID, History> previous = new HashMap<>();
    private final int maxParticlesPerTick;
    private int previousTick = -1;

    public ReplayMovementEffectPlanner(int maxParticlesPerTick) {
        if (maxParticlesPerTick < 1 || maxParticlesPerTick > 64) {
            throw new IllegalArgumentException("Particle budget must be 1..64.");
        }
        this.maxParticlesPerTick = maxParticlesPerTick;
    }

    public List<ReplayMovementEffect> advance(int tick, Map<UUID, ReplayActorPose> poses) {
        Objects.requireNonNull(poses);
        if (tick < 0 || tick <= previousTick) {
            throw new IllegalArgumentException("Playback ticks must strictly increase.");
        }
        int passedTicks = previousTick < 0 ? 1 : Math.min(20, tick - previousTick);
        List<ReplayMovementEffect> landings = new ArrayList<>();
        List<ReplayMovementEffect> sprinting = new ArrayList<>();
        Set<UUID> present = new HashSet<>();
        for (ReplayActorPose pose : poses.values()) {
            present.add(pose.id());
            if (!"PLAYER".equalsIgnoreCase(pose.entityType())) {
                previous.remove(pose.id());
                continue;
            }
            History old = previous.get(pose.id());
            int airborne = pose.onGround() ? 0
                : Math.min(1000, (old == null ? 0 : old.airborneTicks()) + passedTicks);
            if (old != null && validGround(pose) && !pose.gliding()) {
                if (!old.pose().onGround() && pose.onGround()
                    && old.airborneTicks() >= 3) {
                    landings.add(new ReplayMovementEffect(
                        ReplayMovementEffect.Kind.LANDING_DUST, pose, LANDING_PARTICLES));
                } else if (pose.sprinting() && pose.onGround()
                    && tick % SPRINT_INTERVAL_TICKS == 0
                    && horizontalDistanceSquared(old.pose(), pose)
                        >= MIN_HORIZONTAL_DISTANCE_SQUARED) {
                    sprinting.add(new ReplayMovementEffect(
                        ReplayMovementEffect.Kind.SPRINT_DUST, pose, SPRINT_PARTICLES));
                }
            }
            previous.put(pose.id(), new History(pose, airborne));
        }
        previous.keySet().retainAll(present); // Actor removal never leaves stale state.
        previousTick = tick;

        // Landing takes priority under a busy 48-actor scene.
        List<ReplayMovementEffect> emitted = new ArrayList<>();
        int budget = maxParticlesPerTick;
        for (ReplayMovementEffect candidate : landings) {
            if (budget == 0) break;
            int count = Math.min(budget, candidate.particleCount());
            emitted.add(new ReplayMovementEffect(candidate.kind(), candidate.pose(), count));
            budget -= count;
        }
        for (ReplayMovementEffect candidate : sprinting) {
            if (budget == 0) break;
            int count = Math.min(budget, candidate.particleCount());
            emitted.add(new ReplayMovementEffect(candidate.kind(), candidate.pose(), count));
            budget -= count;
        }
        return List.copyOf(emitted);
    }

    private static double horizontalDistanceSquared(ReplayActorPose a, ReplayActorPose b) {
        double dx = b.x() - a.x();
        double dz = b.z() - a.z();
        return dx * dx + dz * dz;
    }

    private static boolean validGround(ReplayActorPose pose) {
        return !pose.groundMaterial().equalsIgnoreCase("AIR")
            && !pose.groundMaterial().equalsIgnoreCase("CAVE_AIR")
            && !pose.groundMaterial().equalsIgnoreCase("VOID_AIR");
    }
}
