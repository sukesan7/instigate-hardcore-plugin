package dev.instigatehardcore.replay;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Freezes only the confirmed run-ending death, BEFORE player reset.
 * The existing countdown and world rotation are left untouched in 9B.
 */
public final class DeathReplayCaptureService {
    private final JavaPlugin plugin;
    private final DeathReplayRecorder frameRecorder;
    private final ReplayCombatRecorder combatRecorder;
    private FrozenDeathReplay lastFrozen;

    public DeathReplayCaptureService(
        JavaPlugin plugin,
        DeathReplayRecorder frameRecorder,
        ReplayCombatRecorder combatRecorder
    ) {
        this.plugin = Objects.requireNonNull(plugin);
        this.frameRecorder = Objects.requireNonNull(frameRecorder);
        this.combatRecorder = Objects.requireNonNull(combatRecorder);
    }

    public Optional<FrozenDeathReplay> freezeOnDeath(
        Player victim, String deathMessage, String deathCause
    ) {
        Objects.requireNonNull(victim);
        Objects.requireNonNull(deathMessage);
        Objects.requireNonNull(deathCause);
        if (!plugin.getServer().isPrimaryThread()) {
            throw new IllegalStateException("Death replay must freeze on main server thread.");
        }

        lastFrozen = null;
        Optional<ReplayClip> possibleClip = frameRecorder.snapshotFor(victim);
        if (possibleClip.isEmpty()) {
            plugin.getLogger().info(
                "No replay frames for " + victim.getName()
                    + "; retaining normal death-location countdown."
            );
            return Optional.empty();
        }
        ReplayClip clip = possibleClip.get();
        Location deathLocation = victim.getLocation().clone();
        ReplayDeathMoment death = new ReplayDeathMoment(
            clip.attempt(), victim.getUniqueId(), deathLocation.getWorld().getUID(),
            frameRecorder.currentSampleTick(), System.currentTimeMillis(),
            deathLocation.getX(), deathLocation.getY(), deathLocation.getZ(),
            deathLocation.getYaw(), deathLocation.getPitch(), deathMessage, deathCause
        );
        long startTick = clip.frames().getFirst().tick();
        List<ReplayCombatEvent> events = combatRecorder.snapshotFor(
            victim.getUniqueId(), clip.attempt(), clip.worldId(),
            startTick, death.tick()
        );
        lastFrozen = new FrozenDeathReplay(clip, death, events);
        plugin.getLogger().info(
            "Frozen Phase 9B death replay: attempt #" + clip.attempt()
                + ", victim=" + victim.getName()
                + ", frames=" + clip.frames().size()
                + ", combatEvents=" + events.size()
                + " (ready for optional playback)."
        );
        return Optional.of(lastFrozen);
    }

    /** Available during ENDING for Phase 9C/9D playback. */
    public Optional<FrozenDeathReplay> lastFrozen() {
        return Optional.ofNullable(lastFrozen);
    }

    public void clear() {
        lastFrozen = null;
        combatRecorder.clear();
    }
}
