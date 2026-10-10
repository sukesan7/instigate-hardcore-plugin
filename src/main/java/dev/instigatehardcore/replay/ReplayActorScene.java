package dev.instigatehardcore.replay;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Deterministic, world-independent 7-second reconstruction of frozen keyframes.
 *
 * No Bukkit objects, worlds or live entities are created here. Consumers can
 * render this scene with a packet-only actor backend in a later phase.
 *
 * When a death occurs shortly after a join/dimension change, the earliest
 * available frame is held rather than inventing events that were not recorded.
 */
public final class ReplayActorScene {
    public static final int DEFAULT_PLAYBACK_TICKS = 140;

    private final FrozenDeathReplay deathReplay;
    private final List<ReplayFrame> frames;
    private final int durationTicks;
    private final long firstTick;
    private final long playbackStartTick;
    private final long lastTick;
    private final long initialHoldTicks;

    public ReplayActorScene(FrozenDeathReplay deathReplay) {
        this(deathReplay, DEFAULT_PLAYBACK_TICKS);
    }

    public ReplayActorScene(FrozenDeathReplay deathReplay, int durationTicks) {
        this.deathReplay = Objects.requireNonNull(deathReplay);
        if (durationTicks < 1 || durationTicks > 600) {
            throw new IllegalArgumentException("Playback duration must be 1..600 ticks.");
        }
        this.durationTicks = durationTicks;
        this.frames = deathReplay.clip().frames();
        this.firstTick = frames.getFirst().tick();
        this.lastTick = frames.getLast().tick();
        for (int i = 1; i < frames.size(); i++) {
            if (frames.get(i).tick() <= frames.get(i - 1).tick()) {
                throw new IllegalArgumentException("Replay frame ticks must increase strictly.");
            }
        }
        this.playbackStartTick = Math.max(firstTick, lastTick - durationTicks);
        long recordedWindow = Math.max(0L, lastTick - playbackStartTick);
        this.initialHoldTicks = Math.max(0L, durationTicks - recordedWindow);
    }

    public int durationTicks() {
        return durationTicks;
    }

    public UUID worldId() {
        return deathReplay.clip().worldId();
    }

    public int attempt() {
        return deathReplay.clip().attempt();
    }

    public ReplayDeathMoment death() {
        return deathReplay.death();
    }

    /**
     * Get immutable actor poses at a playback tick [0,durationTicks].
     * Appearance/equipment are preserved from the last observed keyframe;
     * location/rotation are linearly interpolated between recorded keyframes.
     */
    /**
     * Maps a sampled event tick onto the 7-second playback clock.
     * -1 means the event predates the displayed scene; late terminal events
     * are clamped to the final replay tick.
     */
    public int playbackTickOf(long recordedTick) {
        if (recordedTick < playbackStartTick) {
            return -1;
        }
        return (int) Math.min(durationTicks,
            initialHoldTicks + recordedTick - playbackStartTick);
    }

    public Map<UUID, ReplayActorPose> at(int elapsedTicks) {
        if (elapsedTicks < 0 || elapsedTicks > durationTicks) {
            throw new IllegalArgumentException("Playback tick is outside scene duration.");
        }
        if (elapsedTicks == durationTicks) {
            return deathMomentScene();
        }
        long target = playbackStartTick + Math.max(0L, elapsedTicks - initialHoldTicks);
        if (target <= firstTick) {
            return posesOf(frames.getFirst());
        }
        if (target >= lastTick) {
            return posesOf(frames.getLast());
        }
        int lowerIndex = floorFrameIndex(target);
        ReplayFrame lower = frames.get(lowerIndex);
        ReplayFrame upper = frames.get(lowerIndex + 1);
        double alpha = (double) (target - lower.tick()) / (upper.tick() - lower.tick());
        Map<UUID, ReplayActorSnapshot> nextActors = byId(upper);
        LinkedHashMap<UUID, ReplayActorPose> result = new LinkedHashMap<>();
        for (ReplayActorSnapshot previous : lower.actors()) {
            ReplayActorSnapshot next = nextActors.get(previous.entityId());
            result.put(previous.entityId(), next != null
                && next.entityType().equals(previous.entityType())
                ? ReplayActorPose.interpolate(previous, next, alpha)
                : ReplayActorPose.from(previous));
        }
        return Collections.unmodifiableMap(result);
    }

    private int floorFrameIndex(long tick) {
        int lo = 0;
        int hi = frames.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (frames.get(mid).tick() <= tick) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return Math.min(lo, frames.size() - 2);
    }

    private static Map<UUID, ReplayActorSnapshot> byId(ReplayFrame frame) {
        LinkedHashMap<UUID, ReplayActorSnapshot> indexed = new LinkedHashMap<>();
        for (ReplayActorSnapshot actor : frame.actors()) {
            indexed.put(actor.entityId(), actor);
        }
        return indexed;
    }

    private static Map<UUID, ReplayActorPose> posesOf(ReplayFrame frame) {
        LinkedHashMap<UUID, ReplayActorPose> poses = new LinkedHashMap<>();
        for (ReplayActorSnapshot actor : frame.actors()) {
            poses.put(actor.entityId(), ReplayActorPose.from(actor));
        }
        return Collections.unmodifiableMap(poses);
    }

    private Map<UUID, ReplayActorPose> deathMomentScene() {
        LinkedHashMap<UUID, ReplayActorPose> result = new LinkedHashMap<>(posesOf(frames.getLast()));
        ReplayDeathMoment death = deathReplay.death();
        ReplayActorPose victim = result.get(death.victimId());
        if (victim != null) {
            result.put(death.victimId(), new ReplayActorPose(
                victim.id(), victim.entityType(), victim.name(),
                death.x(), death.y(), death.z(), death.yaw(), death.pitch(),
                victim.sneaking(), victim.gliding(), victim.burning(),
                0.0, victim.equipment()
            ));
        }
        return Collections.unmodifiableMap(result);
    }
}
