package dev.instigatehardcore.replay;

import dev.instigatehardcore.core.RunManager;
import dev.instigatehardcore.stats.StatsManager;
import dev.instigatehardcore.world.WorldSet;
import dev.instigatehardcore.world.WorldSetManager;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 9A: a low-overhead, server-side rolling recorder.
 *
 * Captures only ACTIVE-attempt Survival players and entities in their
 * current dimension. It never spawns replay actors, teleports players,
 * intercepts deaths, or changes the existing rotation/countdown flow.
 *
 * Paper/Bukkit entity access must happen on the primary server thread.
 */
public final class DeathReplayRecorder {

    private final JavaPlugin plugin;
    private final RunManager runManager;
    private final StatsManager statsManager;
    private final WorldSetManager worldSetManager;
    private final int intervalTicks;
    private final int radius;
    private final int maxActorsPerFrame;
    private final int maxFrames;

    private final Map<UUID, RollingReplayBuffer> buffers = new HashMap<>();
    private int recordedAttempt = -1;
    private long sampleTick;
    private BukkitTask task;

    public DeathReplayRecorder(
        JavaPlugin plugin,
        RunManager runManager,
        StatsManager statsManager,
        WorldSetManager worldSetManager,
        int durationSeconds,
        int intervalTicks,
        int radius,
        int maxActorsPerFrame
    ) {
        this.plugin = Objects.requireNonNull(plugin);
        this.runManager = Objects.requireNonNull(runManager);
        this.statsManager = Objects.requireNonNull(statsManager);
        this.worldSetManager = Objects.requireNonNull(worldSetManager);

        if (durationSeconds < 1 || durationSeconds > 30
            || intervalTicks < 1 || intervalTicks > 20
            || radius < 1 || radius > 64
            || maxActorsPerFrame < 1 || maxActorsPerFrame > 128) {
            throw new IllegalArgumentException("Invalid replay recorder limits.");
        }
        this.intervalTicks = intervalTicks;
        this.radius = radius;
        this.maxActorsPerFrame = maxActorsPerFrame;
        this.maxFrames = (durationSeconds * 20 + intervalTicks - 1) / intervalTicks;
    }

    public void start() {
        if (task != null) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(
            plugin, this::captureTick, intervalTicks, intervalTicks
        );
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        buffers.clear();
        recordedAttempt = -1;
    }

    /**
     * Logical tick of the most recent sampled frame. Only access on
     * the primary server thread. Damage events occurring between frames
     * use this tick to align with the nearest preceding sample.
     */
    public long currentSampleTick() {
        if (!plugin.getServer().isPrimaryThread()) {
            throw new IllegalStateException("Replay time must be read on the main server thread.");
        }
        return sampleTick;
    }

    /** Maximum recording window, expressed in logical server ticks. */
    public long windowTicks() {
        return (long) maxFrames * intervalTicks;
    }

        /**
     * Called later by Phase 9B at the real run-ending PlayerDeathEvent.
     * This method never consumes the live buffer.
     */
    public Optional<ReplayClip> snapshotFor(Player victim) {
        Objects.requireNonNull(victim);
        if (!plugin.getServer().isPrimaryThread()) {
            throw new IllegalStateException("Replay snapshot must be requested on the main server thread.");
        }
        int attempt = statsManager.getCurrentAttempt();
        RollingReplayBuffer buffer = buffers.get(victim.getUniqueId());
        if (buffer == null) {
            return Optional.empty();
        }
        List<ReplayFrame> frames = buffer.snapshot();
        if (frames.isEmpty()) {
            return Optional.empty();
        }
        ReplayFrame last = frames.getLast();
        if (last.attempt() != attempt
            || !last.worldId().equals(victim.getWorld().getUID())) {
            return Optional.empty();
        }
        return Optional.of(new ReplayClip(
            attempt, victim.getUniqueId(), last.worldId(), frames
        ));
    }

    private void captureTick() {
        if (!runManager.isActive()) {
            return;
        }
        WorldSet active = worldSetManager.getActiveWorldSet();
        if (active == null) {
            return;
        }
        int attempt = statsManager.getCurrentAttempt();
        if (attempt != recordedAttempt) {
            buffers.clear();
            recordedAttempt = attempt;
        }
        sampleTick += intervalTicks;

        Set<UUID> online = new HashSet<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!player.isOnline()) {
                continue;
            }
            online.add(player.getUniqueId());
            if (player.isDead() || player.getGameMode() != GameMode.SURVIVAL
                || !active.contains(player.getWorld())) {
                continue;
            }
            try {
                capturePlayer(player, attempt);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning(
                    "Unable to capture replay frame for " + player.getName()
                        + ": " + exception.getMessage()
                );
            }
        }
        // Online-but-ineligible player buffers are kept only until a new
        // attempt begins. Disconnected player buffers are discarded now.
        buffers.keySet().retainAll(online);
    }

    private void capturePlayer(Player subject, int attempt) {
        List<ReplayActorSnapshot> actors = new ArrayList<>(maxActorsPerFrame);
        actors.add(toSnapshot(subject));

        List<Entity> nearby = subject.getNearbyEntities(radius, radius, radius);
        nearby.stream()
            .filter(entity -> entity instanceof LivingEntity || entity instanceof Projectile)
            .filter(entity -> !entity.isDead())
            .filter(entity -> !(entity instanceof Player other
                && other.getGameMode() != GameMode.SURVIVAL))
            .filter(entity -> entity.getLocation().distanceSquared(subject.getLocation())
                <= (double) radius * radius)
            .sorted(Comparator.comparingDouble(entity ->
                entity.getLocation().distanceSquared(subject.getLocation())))
            .limit(maxActorsPerFrame - 1L)
            .map(this::toSnapshot)
            .forEach(actors::add);

        ReplayFrame frame = new ReplayFrame(
            sampleTick,
            attempt,
            subject.getWorld().getUID(),
            subject.getUniqueId(),
            actors
        );
        buffers.computeIfAbsent(
            subject.getUniqueId(), ignored -> new RollingReplayBuffer(maxFrames)
        ).append(frame);
    }

    private ReplayActorSnapshot toSnapshot(Entity entity) {
        Location location = entity.getLocation();
        LivingEntity living = entity instanceof LivingEntity le ? le : null;
        Player player = entity instanceof Player p ? p : null;
        EntityEquipment equipment = living == null ? null : living.getEquipment();

        ReplayEquipment visualEquipment = equipment == null
            ? ReplayEquipment.empty()
            : new ReplayEquipment(
                materialName(equipment.getItemInMainHand()),
                materialName(equipment.getItemInOffHand()),
                materialName(equipment.getHelmet()),
                materialName(equipment.getChestplate()),
                materialName(equipment.getLeggings()),
                materialName(equipment.getBoots())
            );

        // Ground type is recorded at capture time, not reconstructed from the
        // later world state. Only players need movement dust in Phase 9F.3.
        boolean onGround = player != null && player.isOnGround();
        String groundMaterial = "AIR";
        if (onGround) {
            groundMaterial = location.getWorld().getBlockAt(
                location.getBlockX(),
                (int) Math.floor(location.getY() - 0.12),
                location.getBlockZ()
            ).getType().name();
        }

        ReplayCreeperState creeperState = entity instanceof Creeper creeper
            ? new ReplayCreeperState(
                Math.max(0, creeper.getFuseTicks()),
                Math.max(1, creeper.getMaxFuseTicks()),
                creeper.isIgnited(), creeper.isPowered()
            )
            : ReplayCreeperState.NONE;

        return new ReplayActorSnapshot(
            entity.getUniqueId(),
            entity.getType().name(),
            player != null ? player.getName() : entity.getName(),
            location.getX(), location.getY(), location.getZ(),
            location.getYaw(), location.getPitch(),
            player != null && player.isSneaking(),
            living != null && living.isGliding(),
            entity.getFireTicks() > 0,
            living != null ? living.getHealth() : -1.0,
            visualEquipment,
            player != null && player.isSprinting(),
            onGround,
            groundMaterial,
            creeperState
        );
    }

    private static String materialName(ItemStack item) {
        return item == null ? "AIR" : item.getType().name();
    }
}
