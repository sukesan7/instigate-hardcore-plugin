package dev.instigatehardcore.replay;

import dev.instigatehardcore.core.RunManager;
import dev.instigatehardcore.stats.StatsManager;
import dev.instigatehardcore.world.WorldSetManager;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Captures damage events near active Survival players, in memory only.
 * This is intentionally observation-only; it does not modify damage.
 */
public final class ReplayCombatRecorder implements Listener {
    private static final int MAX_EVENTS_PER_PLAYER = 256;

    private final JavaPlugin plugin;
    private final RunManager runManager;
    private final StatsManager statsManager;
    private final WorldSetManager worldSetManager;
    private final DeathReplayRecorder frameRecorder;
    private final int radius;
    private final Map<UUID, RollingReplayEventBuffer> buffers = new HashMap<>();
    private int recordedAttempt = -1;

    public ReplayCombatRecorder(
        JavaPlugin plugin,
        RunManager runManager,
        StatsManager statsManager,
        WorldSetManager worldSetManager,
        DeathReplayRecorder frameRecorder,
        int radius
    ) {
        this.plugin = Objects.requireNonNull(plugin);
        this.runManager = Objects.requireNonNull(runManager);
        this.statsManager = Objects.requireNonNull(statsManager);
        this.worldSetManager = Objects.requireNonNull(worldSetManager);
        this.frameRecorder = Objects.requireNonNull(frameRecorder);
        if (radius < 1 || radius > 64) {
            throw new IllegalArgumentException("Invalid replay capture radius.");
        }
        this.radius = radius;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!runManager.isActive()) {
            return;
        }
        Entity damaged = event.getEntity();
        if (!worldSetManager.isActiveWorld(damaged.getWorld())) {
            return;
        }

        int attempt = statsManager.getCurrentAttempt();
        if (recordedAttempt != attempt) {
            buffers.clear();
            recordedAttempt = attempt;
        }
        long tick = frameRecorder.currentSampleTick();
        Location position = damaged.getLocation();
        Entity attacker = resolveAttacker(event);
        ReplayCombatEvent recorded = new ReplayCombatEvent(
            tick, attempt, damaged.getWorld().getUID(),
            damaged.getUniqueId(), damaged.getType().name(),
            attacker == null ? null : attacker.getUniqueId(),
            attacker == null ? "ENVIRONMENT" : attacker.getType().name(),
            event.getCause().name(), Math.max(0.0, event.getFinalDamage()),
            position.getX(), position.getY(), position.getZ()
        );

        double maxDistanceSquared = (double) radius * radius;
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (!viewer.isOnline() || viewer.isDead()
                || viewer.getGameMode() != GameMode.SURVIVAL
                || viewer.getWorld() != damaged.getWorld()
                || viewer.getLocation().distanceSquared(position) > maxDistanceSquared) {
                continue;
            }
            buffers.computeIfAbsent(
                viewer.getUniqueId(),
                ignored -> new RollingReplayEventBuffer(
                    MAX_EVENTS_PER_PLAYER, frameRecorder.windowTicks()
                )
            ).append(recorded);
        }
    }

    /** Read events for the victim's exact recording window. Main thread only. */
    public List<ReplayCombatEvent> snapshotFor(
        UUID victimId, int attempt, UUID worldId, long startTick, long endTick
    ) {
        if (!plugin.getServer().isPrimaryThread()) {
            throw new IllegalStateException("Combat replay snapshot requires main thread.");
        }
        RollingReplayEventBuffer buffer = buffers.get(victimId);
        return buffer == null ? List.of()
            : buffer.snapshot(attempt, worldId, startTick, endTick);
    }

    public void clear() {
        buffers.clear();
        recordedAttempt = -1;
    }

    private static Entity resolveAttacker(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            return null;
        }
        Entity direct = byEntity.getDamager();
        if (direct instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Entity shooterEntity) {
                return shooterEntity;
            }
        }
        return direct;
    }
}
