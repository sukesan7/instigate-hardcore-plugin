package dev.instigatehardcore.replay;

import dev.instigatehardcore.core.RunManager;
import dev.instigatehardcore.stats.StatsManager;
import dev.instigatehardcore.world.WorldSetManager;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
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
    private static final int MAX_VISUAL_EVENTS_PER_PLAYER = 384;

    private final JavaPlugin plugin;
    private final RunManager runManager;
    private final StatsManager statsManager;
    private final WorldSetManager worldSetManager;
    private final DeathReplayRecorder frameRecorder;
    private final int radius;
    private final Map<UUID, RollingReplayEventBuffer> buffers = new HashMap<>();
    private final Map<UUID, RollingReplayVisualEventBuffer> visualBuffers = new HashMap<>();
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
        resetForAttempt(attempt);
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

        if (event.getFinalDamage() > 0.0) {
            appendVisual(attempt, tick, damaged,
                damaged.getUniqueId(), ReplayVisualEvent.Kind.HURT);
            if (event instanceof EntityDamageByEntityEvent byEntity) {
                if (byEntity.isCritical()) {
                    // Critical hit particles surround the TARGET, not attacker.
                    appendVisual(attempt, tick, damaged,
                        damaged.getUniqueId(), ReplayVisualEvent.Kind.CRITICAL);
                }
                // Mob attacks have no PlayerAnimationEvent. Avoid duplicating
                // player swings (including missed swings) captured below.
                Entity direct = byEntity.getDamager();
                if (direct instanceof LivingEntity && !(direct instanceof Player)) {
                    appendVisual(attempt, tick, damaged,
                        direct.getUniqueId(), ReplayVisualEvent.Kind.SWING_MAIN);
                }
            }
        }
    }

    /** Records actual client arm swings, including attacks that miss. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerAnimation(PlayerAnimationEvent event) {
        Player player = event.getPlayer();
        if (!runManager.isActive() || player.isDead()
            || player.getGameMode() != GameMode.SURVIVAL
            || !worldSetManager.isActiveWorld(player.getWorld())) {
            return;
        }
        int attempt = statsManager.getCurrentAttempt();
        resetForAttempt(attempt);
        ReplayVisualEvent.Kind kind;
        if (event.getAnimationType()
            == org.bukkit.event.player.PlayerAnimationType.ARM_SWING) {
            kind = ReplayVisualEvent.Kind.SWING_MAIN;
        } else if (event.getAnimationType()
            == org.bukkit.event.player.PlayerAnimationType.OFF_ARM_SWING) {
            kind = ReplayVisualEvent.Kind.SWING_OFF;
        } else {
            return;
        }
        appendVisual(attempt, frameRecorder.currentSampleTick(), player,
            player.getUniqueId(), kind);
    }

    private void appendVisual(
        int attempt, long tick, Entity atEntity, UUID actorId,
        ReplayVisualEvent.Kind kind
    ) {
        Location location = atEntity.getLocation();
        ReplayVisualEvent visual = new ReplayVisualEvent(
            tick, attempt, atEntity.getWorld().getUID(), actorId, kind
        );
        double range2 = (double) radius * radius;
        for (Player subject : plugin.getServer().getOnlinePlayers()) {
            if (!subject.isOnline() || subject.isDead()
                || subject.getGameMode() != GameMode.SURVIVAL
                || subject.getWorld() != atEntity.getWorld()
                || subject.getLocation().distanceSquared(location) > range2) {
                continue;
            }
            visualBuffers.computeIfAbsent(subject.getUniqueId(), ignored ->
                new RollingReplayVisualEventBuffer(
                    MAX_VISUAL_EVENTS_PER_PLAYER, frameRecorder.windowTicks()
                )
            ).append(visual);
        }
    }

    private void resetForAttempt(int attempt) {
        if (recordedAttempt != attempt) {
            buffers.clear();
            visualBuffers.clear();
            recordedAttempt = attempt;
        }
    }

    /** Read visual events for the same victim window as the keyframes. */
    public List<ReplayVisualEvent> snapshotVisualFor(
        UUID victimId, int attempt, UUID worldId, long startTick, long endTick
    ) {
        if (!plugin.getServer().isPrimaryThread()) {
            throw new IllegalStateException("Visual snapshot requires main thread.");
        }
        RollingReplayVisualEventBuffer buffer = visualBuffers.get(victimId);
        return buffer == null ? List.of() :
            buffer.snapshot(attempt, worldId, startTick, endTick);
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
        visualBuffers.clear();
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
