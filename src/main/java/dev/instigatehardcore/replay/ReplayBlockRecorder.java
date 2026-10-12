package dev.instigatehardcore.replay;

import dev.instigatehardcore.core.RunManager;
import dev.instigatehardcore.stats.StatsManager;
import dev.instigatehardcore.world.WorldSetManager;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Bisected;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.FluidLevelChangeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded, main-thread-only recent block history. Observes successful player
 * placement/breaking, explosion destruction and selected environmental
 * changes without modifying the world. Environment events are watched for
 * up to three ticks so the actual result of vanilla physics is recorded,
 * rather than guessing the resulting block state in an event callback.
 * No block entity inventories or falling-entity motion are serialized.
 */
public final class ReplayBlockRecorder implements Listener {
    private static final String AIR = "minecraft:air";
    private static final int MAX_RECENT_CHANGES = 8192;

    private final JavaPlugin plugin;
    private final RunManager runManager;
    private final StatsManager statsManager;
    private final WorldSetManager worlds;
    private final DeathReplayRecorder frames;
    private final int radius;
    private final int clipLimit;
    private final ArrayDeque<ReplayBlockChange> recent = new ArrayDeque<>();
    private int activeAttempt = -1;

    // Environmental state sampling is independently configurable and bounded.
    private final boolean environmentEnabled;
    private final ReplayEnvironmentWatchQueue environmentQueue;
    private BukkitTask environmentTask;

    // Only explosion block lists in the death tick (or immediately following
    // tick) may be appended after ACTIVE -> ENDING. Prevent later resets or
    // unrelated world activity from polluting the frozen recording.
    private ReplayTerminalExplosionWindow terminalWindow;

    public ReplayBlockRecorder(
        JavaPlugin plugin, RunManager runManager, StatsManager statsManager,
        WorldSetManager worlds, DeathReplayRecorder frames,
        int radius, int clipLimit
    ) {
        this.plugin = Objects.requireNonNull(plugin);
        this.runManager = Objects.requireNonNull(runManager);
        this.statsManager = Objects.requireNonNull(statsManager);
        this.worlds = Objects.requireNonNull(worlds);
        this.frames = Objects.requireNonNull(frames);
        this.radius = Math.max(1, Math.min(64, radius));
        this.clipLimit = Math.max(1, Math.min(4096, clipLimit));
        this.environmentEnabled = false;
        this.environmentQueue = new ReplayEnvironmentWatchQueue(512, 128);
    }

    /** Phase 10B constructor; the earlier constructor stays compatible. */
    public ReplayBlockRecorder(
        JavaPlugin plugin, RunManager runManager, StatsManager statsManager,
        WorldSetManager worlds, DeathReplayRecorder frames, int radius,
        int clipLimit, boolean environmentEnabled,
        int maxWatchedBlocks, int maxReadsPerTick
    ) {
        this.plugin = Objects.requireNonNull(plugin);
        this.runManager = Objects.requireNonNull(runManager);
        this.statsManager = Objects.requireNonNull(statsManager);
        this.worlds = Objects.requireNonNull(worlds);
        this.frames = Objects.requireNonNull(frames);
        this.radius = Math.max(1, Math.min(64, radius));
        this.clipLimit = Math.max(1, Math.min(4096, clipLimit));
        this.environmentEnabled = environmentEnabled;
        this.environmentQueue = new ReplayEnvironmentWatchQueue(
            Math.max(1, maxWatchedBlocks), Math.max(1, maxReadsPerTick));
    }

    /** Poll only while this optional recorder is installed. Main thread. */
    public void start() {
        if (!environmentEnabled || environmentTask != null) return;
        environmentTask = plugin.getServer().getScheduler().runTaskTimer(
            plugin, this::sampleEnvironmentalChanges, 1L, 1L);
    }

    public void stop() {
        if (environmentTask != null) {
            environmentTask.cancel();
            environmentTask = null;
        }
        clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        record(event.getBlockPlaced(),
            event.getBlockReplacedState().getBlockData().getAsString(),
            event.getBlockPlaced().getBlockData().getAsString());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        record(event.getBlock(), event.getBlock().getBlockData().getAsString(), AIR);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        for (Block block : event.blockList()) {
            record(block, block.getBlockData().getAsString(), AIR, true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        for (Block block : event.blockList()) {
            record(block, block.getBlockData().getAsString(), AIR, true);
        }
    }

    /*
     * ------------------------------------------------------------
     * PHASE 10B — BOUNDED ENVIRONMENTAL HISTORY
     * ------------------------------------------------------------
     * These events generally fire BEFORE vanilla has modified the world.
     * Watching the affected blocks, then reading them next tick, records
     * actual transitions without simulating Minecraft physics.
     */

    /**
     * Phase 10B.1: bucket actions do not reliably emit BlockPlaceEvent or
     * BlockBreakEvent. For ordinary source blocks the new state is known,
     * so record it at event time (also catches rapid put-down/pick-up).
     * Waterlogged/special targets use a bounded post-event state read.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!environmentEnabled) return;
        Block block = event.getBlock();
        String before = block.getBlockData().getAsString();
        String after = ReplayFluidState.placedSource(event.getBucket().name(), before);
        if (after != null) {
            recordFluidTransition(block, before, after);
        } else if (block.getBlockData() instanceof Waterlogged) {
            BlockData candidate = block.getBlockData().clone();
            ((Waterlogged) candidate).setWaterlogged(true);
            recordFluidTransition(block, before, candidate.getAsString());
        } else {
            watch(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!environmentEnabled) return;
        Block block = event.getBlock();
        String before = block.getBlockData().getAsString();
        String after = ReplayFluidState.pickedUpSource(before);
        if (after != null) {
            recordFluidTransition(block, before, after);
        } else if (block.getBlockData() instanceof Waterlogged) {
            BlockData candidate = block.getBlockData().clone();
            ((Waterlogged) candidate).setWaterlogged(false);
            recordFluidTransition(block, before, candidate.getAsString());
        } else {
            watch(block);
        }
    }

    /**
     * FluidLevelChangeEvent supplies vanilla's chosen destination BlockData,
     * including each flowing level. This avoids the three-tick poll window
     * accidentally merging or missing drainage steps.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFluidLevelChange(FluidLevelChangeEvent event) {
        if (!environmentEnabled) return;
        Block block = event.getBlock();
        recordFluidTransition(block, block.getBlockData().getAsString(),
            event.getNewData().getAsString());
    }

    /** Fallback for updates not surfaced as FluidLevelChangeEvent. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFluidMove(BlockFromToEvent event) {
        watch(event.getBlock());
        watch(event.getToBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        watchPiston(event.getBlock(), event.getDirection(), event.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        watchPiston(event.getBlock(), event.getDirection(), event.getBlocks());
    }

    private void watchPiston(Block piston, org.bukkit.block.BlockFace direction,
                             List<Block> moved) {
        watch(piston);
        watch(piston.getRelative(direction));
        // Both directions are watched because retracting sticky pistons move
        // blocks backwards, whereas extending pistons move them forwards.
        for (Block block : moved) {
            watch(block);
            watch(block.getRelative(direction));
            watch(block.getRelative(direction.getOppositeFace()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFallingBlockTransition(EntityChangeBlockEvent event) {
        watch(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) { watch(event.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) { watch(event.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        Block block = event.getBlock();
        String before = block.getBlockData().getAsString();
        // When fluid becomes air, record the precise transition now rather
        // than hoping the pending watch catches the disappearance next tick.
        if (environmentEnabled && ReplayFluidState.isFluid(before)) {
            recordFluidTransition(block, before,
                event.getNewState().getBlockData().getAsString());
        } else {
            watch(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) { watch(event.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        Block block = event.getBlock();
        String before = block.getBlockData().getAsString();
        if (environmentEnabled && ReplayFluidState.isFluid(before)) {
            recordFluidTransition(block, before,
                event.getNewState().getBlockData().getAsString());
        } else {
            watch(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) { watch(event.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
            || event.useInteractedBlock() == Event.Result.DENY) return;
        Block block = event.getClickedBlock();
        if (block == null || !(block.getBlockData() instanceof Openable)) return;
        watch(block);
        // Upper and lower halves of doors update together.
        if (block.getBlockData() instanceof Bisected) {
            watch(block.getRelative(org.bukkit.block.BlockFace.UP));
            watch(block.getRelative(org.bukkit.block.BlockFace.DOWN));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRedstone(BlockRedstoneEvent event) {
        // Catch powered doors, trapdoors, lamps and comparable neighboring
        // block-state changes, but only in the active recording radius.
        Block source = event.getBlock();
        watch(source);
        for (org.bukkit.block.BlockFace face : new org.bukkit.block.BlockFace[]{
            org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockFace.DOWN,
            org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH,
            org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST
        }) watch(source.getRelative(face));
    }

    /** Discard stale sampled state whenever we have an authoritative event. */
    private void recordFluidTransition(Block block, String before, String after) {
        if (!environmentEnabled || !runManager.isActive()
            || !worlds.isActiveWorld(block.getWorld())) return;
        environmentQueue.discard(new ReplayEnvironmentWatchQueue.Key(
            block.getWorld().getUID(),
            new ReplayBlockPosition(block.getX(), block.getY(), block.getZ())));
        record(block, before, after);
    }

    private void watch(Block block) {
        if (!environmentEnabled || !runManager.isActive()) return;
        World world = block.getWorld();
        if (!worlds.isActiveWorld(world) || block.getY() < world.getMinHeight()
            || block.getY() >= world.getMaxHeight()
            || !world.isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) return;
        ReplayEnvironmentWatchQueue.Key key = new ReplayEnvironmentWatchQueue.Key(
            world.getUID(), new ReplayBlockPosition(
                block.getX(), block.getY(), block.getZ()));
        if (!environmentQueue.canAccept(key) || !nearActivePlayer(block)) return;
        int attempt = statsManager.getCurrentAttempt();
        ensureAttempt(attempt);
        environmentQueue.watch(key, block.getBlockData().getAsString(),
            attempt, frames.currentSampleTick(), Bukkit.getCurrentTick());
    }

    private void sampleEnvironmentalChanges() {
        if (environmentQueue.size() == 0) return;
        long serverTick = Bukkit.getCurrentTick();
        long frameTick = frames.currentSampleTick();
        for (ReplayEnvironmentWatchQueue.Change change : environmentQueue.sample(
            serverTick, frameTick, this::readEnvironmentState)) {
            if (!runManager.isActive()
                || change.attempt() != statsManager.getCurrentAttempt()) continue;
            World world = Bukkit.getWorld(change.key().worldId());
            if (world == null || !worlds.isActiveWorld(world)) continue;
            ReplayBlockPosition pos = change.key().position();
            if (!world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) continue;
            record(world.getBlockAt(pos.x(), pos.y(), pos.z()),
                change.beforeData(), change.afterData(), false, change.tick());
        }
    }

    private String readEnvironmentState(ReplayEnvironmentWatchQueue.Key key) {
        World world = Bukkit.getWorld(key.worldId());
        if (world == null) return null;
        ReplayBlockPosition pos = key.position();
        if (pos.y() < world.getMinHeight() || pos.y() >= world.getMaxHeight()
            || !world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) return null;
        try {
            return world.getBlockAt(pos.x(), pos.y(), pos.z())
                .getBlockData().getAsString();
        } catch (RuntimeException unavailable) {
            // Chunk/world transitions should not affect the attempt lifecycle.
            return null;
        }
    }

    private void ensureAttempt(int attempt) {
        if (activeAttempt != attempt) {
            recent.clear();
            environmentQueue.clear();
            activeAttempt = attempt;
        }
    }

    /** Called on the first confirmed attempt-ending death. Main thread. */
    public void markTerminalDeath(Location deathLocation) {
        Objects.requireNonNull(deathLocation);
        terminalWindow = new ReplayTerminalExplosionWindow(
            statsManager.getCurrentAttempt(), deathLocation.getWorld().getUID(),
            Bukkit.getCurrentTick(), deathLocation.getX(),
            deathLocation.getY(), deathLocation.getZ(), radius);
    }

    private void record(Block block, String before, String after) {
        record(block, before, after, false);
    }

    private void record(Block block, String before, String after, boolean explosion) {
        record(block, before, after, explosion, frames.currentSampleTick());
    }

    private void record(Block block, String before, String after,
                        boolean explosion, long tick) {
        if (!worlds.isActiveWorld(block.getWorld()) || before.equals(after)) {
            return;
        }
        boolean terminalExplosion = explosion && runManager.isEnding()
            && terminalWindow != null
            && terminalWindow.accepts(statsManager.getCurrentAttempt(),
                block.getWorld().getUID(), Bukkit.getCurrentTick(),
                block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5);
        if (!(runManager.isActive() && nearActivePlayer(block))
            && !terminalExplosion) {
            return;
        }
        int attempt = statsManager.getCurrentAttempt();
        ensureAttempt(attempt);
        if (tick <= 0) return;
        // The same vanilla transition can be observed via an exact fluid
        // event and a delayed physics watch; never replay it twice.
        var backwards = recent.descendingIterator();
        int scanned = 0;
        while (backwards.hasNext() && scanned++ < 64) {
            ReplayBlockChange previous = backwards.next();
            if (previous.tick() < tick - 2) break;
            if (previous.tick() == tick && previous.attempt() == attempt
                && previous.worldId().equals(block.getWorld().getUID())
                && previous.position().equals(new ReplayBlockPosition(
                    block.getX(), block.getY(), block.getZ()))
                && previous.beforeData().equals(before)
                && previous.afterData().equals(after)) return;
        }
        recent.addLast(new ReplayBlockChange(tick, attempt, block.getWorld().getUID(),
            new ReplayBlockPosition(block.getX(), block.getY(), block.getZ()),
            before, after));
        long oldest = tick - frames.windowTicks() - 2;
        while (!recent.isEmpty() && (recent.size() > MAX_RECENT_CHANGES
            || recent.peekFirst().tick() < oldest)) {
            recent.removeFirst();
        }
    }

    private boolean nearActivePlayer(Block block) {
        double radiusSquared = (double) radius * radius;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!player.isDead() && player.getGameMode() == GameMode.SURVIVAL
                && player.getWorld().equals(block.getWorld())
                && player.getLocation().distanceSquared(block.getLocation().add(0.5, 0.5, 0.5))
                    <= radiusSquared) {
                return true;
            }
        }
        return false;
    }

    /** Freeze only blocks close to the recorded victim along their clip path. */
    public List<ReplayBlockChange> snapshotFor(ReplayClip clip) {
        Objects.requireNonNull(clip);
        if (!plugin.getServer().isPrimaryThread()) {
            throw new IllegalStateException("Block history must be read on the primary thread");
        }
        if (clip.attempt() != activeAttempt) return List.of();
        long start = clip.frames().getFirst().tick();
        long end = clip.frames().getLast().tick();
        List<ReplayBlockChange> out = new ArrayList<>();
        double radiusSquared = (double) radius * radius;
        for (ReplayBlockChange change : recent) {
            if (change.tick() < start || change.tick() > end
                || change.attempt() != clip.attempt()
                || !change.worldId().equals(clip.worldId())) continue;
            if (nearRecordedVictim(change.position(), clip, radiusSquared)) {
                out.add(change);
                if (out.size() >= clipLimit) break;
            }
        }
        return List.copyOf(out);
    }

    private static boolean nearRecordedVictim(
        ReplayBlockPosition pos, ReplayClip clip, double radiusSquared
    ) {
        for (ReplayFrame frame : clip.frames()) {
            for (ReplayActorSnapshot actor : frame.actors()) {
                if (!actor.entityId().equals(clip.victimId())) continue;
                double dx = pos.x() + 0.5 - actor.x();
                double dy = pos.y() + 0.5 - actor.y();
                double dz = pos.z() + 0.5 - actor.z();
                if (dx * dx + dy * dy + dz * dz <= radiusSquared) return true;
                break;
            }
        }
        return false;
    }

    public void clear() {
        recent.clear();
        activeAttempt = -1;
        terminalWindow = null;
        environmentQueue.clear();
    }
}
