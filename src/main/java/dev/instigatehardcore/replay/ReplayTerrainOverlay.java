package dev.instigatehardcore.replay;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;

/**
 * Viewer-only block overrides. Never edits the world or block entities.
 * Updates are bounded each tick, and visible overrides are restored from
 * the real world on cleanup. Missing chunks are left to the client/server.
 */
public final class ReplayTerrainOverlay implements AutoCloseable {
    private final Player viewer;
    private final World world;
    private final ReplayTerrainTimeline timeline;
    private final int limit;
    private final LinkedHashMap<ReplayBlockPosition, String> pending = new LinkedHashMap<>();
    private final Set<ReplayBlockPosition> touched = new HashSet<>();
    private final Map<ReplayBlockPosition, String> desired = new LinkedHashMap<>();
    private final Map<String, BlockData> parsed = new HashMap<>();
    private boolean closed;

    public ReplayTerrainOverlay(
        Player viewer, ReplayActorScene scene,
        List<ReplayBlockChange> changes, int maxPacketsPerTick
    ) {
        this.viewer = Objects.requireNonNull(viewer);
        Objects.requireNonNull(scene);
        this.world = Objects.requireNonNull(Bukkit.getWorld(scene.worldId()),
            "Replay world is no longer loaded");
        this.timeline = new ReplayTerrainTimeline(scene, changes);
        this.limit = Math.max(1, Math.min(512, maxPacketsPerTick));
        desired.putAll(timeline.initialStates());
        pending.putAll(desired);
    }

    public void renderTick(int tick) {
        if (closed || !viewer.isOnline() || !viewer.getWorld().equals(world)) return;
        for (ReplayBlockChange change : timeline.changesAt(tick)) {
            desired.put(change.position(), change.afterData());
            pending.put(change.position(), change.afterData());
        }
        // Spectator teleport may load chunks a few ticks after the initial
        // override. Resend the current historical states during warm-up, not
        // the starting state (which might now be obsolete).
        if (tick == 8 || tick == 16) {
            pending.putAll(desired);
        }
        int count = 0;
        while (count < limit && !pending.isEmpty()) {
            var entry = pending.entrySet().iterator().next();
            ReplayBlockPosition position = entry.getKey();
            String blockState = entry.getValue();
            pending.remove(position);
            Location at = new Location(world, position.x(), position.y(), position.z());
            try {
                viewer.sendBlockChange(at,
                    parsed.computeIfAbsent(blockState, Bukkit::createBlockData));
                touched.add(position);
            } catch (IllegalArgumentException invalidBlockData) {
                // One unsupported state should not cancel the entire Deathcam.
            }
            count++;
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        pending.clear();
        desired.clear();
        if (!viewer.isOnline() || !viewer.getWorld().equals(world)) {
            touched.clear();
            return;
        }
        // The actual world stays authoritative; send the latest real states.
        for (ReplayBlockPosition position : touched) {
            try {
                if (!world.isChunkLoaded(position.x() >> 4, position.z() >> 4)) {
                    continue;
                }
                Location at = new Location(world, position.x(), position.y(), position.z());
                viewer.sendBlockChange(at, world.getBlockAt(
                    position.x(), position.y(), position.z()).getBlockData());
            } catch (RuntimeException ignored) {
                // A chunk or viewer can disappear mid-cleanup; restore others.
            }
        }
        touched.clear();
    }
}
