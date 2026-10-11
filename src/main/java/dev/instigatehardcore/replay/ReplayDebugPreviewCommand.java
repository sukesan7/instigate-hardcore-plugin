package dev.instigatehardcore.replay;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Opt-in operator preview while the attempt is ACTIVE. Safe independent testing
 * of 9D packets; it does not end an attempt or change the 8F death lifecycle.
 *
 * Usage: /hcreplaytest <survival-player> [pov]
 * The command issuer must be SPECTATOR within 48 blocks of the target.
 */
public final class ReplayDebugPreviewCommand implements CommandExecutor, Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final DeathReplayRecorder recorder;
    private final ReplayCombatRecorder combatRecorder;
    private final Map<UUID, Preview> active = new HashMap<>();

    private final class Preview implements AutoCloseable {
        final Player viewer;
        final UUID worldId;
        final ReplayPacketActorTransport transport;
        final ReplayActorPlayback playback;
        final ReplayActorScene scene;
        final ReplayClip clip;
        final boolean pov;
        final UUID victimId;
        final List<Entity> hiddenLiveActors = new ArrayList<>();
        BukkitTask tickTask;
        int tick;
        boolean focused;
        boolean closed;

        Preview(
            Player viewer, ReplayActorScene scene, ReplayClip clip,
            boolean pov, List<ReplayVisualEvent> events,
            List<ReplayCreeperExplosionEvent> explosions
        ) {
            this.viewer = viewer;
            this.worldId = scene.worldId();
            this.scene = scene;
            this.clip = clip;
            this.pov = pov;
            this.victimId = scene.death().victimId();
            this.transport = new ReplayPacketActorTransport(viewer, scene.worldId());
            this.playback = new ReplayActorPlayback(
                scene, transport, events, explosions,
                plugin.getConfig().getBoolean("death-replay.visuals.movement-particles", true),
                plugin.getConfig().getInt("death-replay.visuals.max-particles-per-tick", 24)
            );
        }

        void start() {
            // Keep the viewer from seeing real entities overlaid on their own ghosts.
            // hideEntity() only affects this viewer and is reversed in close().
            var actorIds = new HashSet<UUID>();
            for (ReplayFrame frame : sceneFrames()) {
                for (ReplayActorSnapshot actor : frame.actors()) {
                    actorIds.add(actor.entityId());
                }
            }
            for (UUID actorId : actorIds) {
                Entity live = Bukkit.getEntity(actorId);
                if (live != null && !live.equals(viewer) && live.getWorld().getUID().equals(worldId)) {
                    viewer.hideEntity(plugin, live);
                    hiddenLiveActors.add(live);
                }
            }
            tickTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (closed) {
                    return;
                }
                if (!viewer.isOnline() || viewer.getGameMode() != GameMode.SPECTATOR
                    || !viewer.getWorld().getUID().equals(worldId)) {
                    finish(viewer.getUniqueId());
                    return;
                }
                try {
                    playback.renderTick(tick);
                    if (pov && !focused) {
                        focused = transport.focusOnActor(victimId);
                    }
                    if (tick++ >= scene.durationTicks()) {
                        finish(viewer.getUniqueId());
                    }
                } catch (RuntimeException exception) {
                    plugin.getLogger().log(Level.WARNING, "Death replay preview failed; cleaning up ghost actors.", exception);
                    finish(viewer.getUniqueId());
                }
            }, 1L, 1L);
        }

        private List<ReplayFrame> sceneFrames() {
            // The source frames are immutable; expose them here rather than duplicating them.
            return clip.frames();
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (tickTask != null) {
                tickTask.cancel();
            }
            try {
                playback.close();
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Replay ghost cleanup was incomplete.", exception);
            } finally {
                if (viewer.isOnline()) {
                    for (Entity live : hiddenLiveActors) {
                        viewer.showEntity(plugin, live);
                    }
                }
                hiddenLiveActors.clear();
            }
        }
    }

    public ReplayDebugPreviewCommand(
        JavaPlugin plugin, DeathReplayRecorder recorder, ReplayCombatRecorder combatRecorder
    ) {
        this.plugin = Objects.requireNonNull(plugin);
        this.recorder = Objects.requireNonNull(recorder);
        this.combatRecorder = Objects.requireNonNull(combatRecorder);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("instigatehardcore.debug")) {
            sender.sendMessage("You do not have permission to preview death replays.");
            return true;
        }
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage("This command must be run by a player.");
            return true;
        }
        if (!plugin.getConfig().getBoolean("death-replay.debug-preview-enabled", false)) {
            viewer.sendMessage("Replay preview is disabled in config.yml.");
            return true;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("packetevents")) {
            viewer.sendMessage("Install and enable PacketEvents 2.14.0 to test the replay renderer.");
            return true;
        }
        if (args.length < 1 || args.length > 2) {
            viewer.sendMessage("Usage: /hcreplaytest <survival-player> [pov]");
            return true;
        }
        if (viewer.getGameMode() != GameMode.SPECTATOR) {
            viewer.sendMessage("Switch to spectator before testing: /gamemode spectator");
            return true;
        }
        Player subject = Bukkit.getPlayerExact(args[0]);
        if (subject == null || !subject.isOnline() || subject.getGameMode() != GameMode.SURVIVAL) {
            viewer.sendMessage("Target must be an online Survival player.");
            return true;
        }
        Location here = viewer.getLocation();
        if (!here.getWorld().getUID().equals(subject.getWorld().getUID())
            || here.distanceSquared(subject.getLocation()) > 48 * 48) {
            viewer.sendMessage("Spectate within 48 blocks of the target, in the same dimension.");
            return true;
        }

        Optional<ReplayClip> possible = recorder.snapshotFor(subject);
        if (possible.isEmpty()) {
            viewer.sendMessage("No recorded frames yet. Wait several seconds and retry.");
            return true;
        }
        ReplayClip clip = possible.get();
        ReplayActorSnapshot lastVictim = clip.frames().getLast().actors().stream()
            .filter(actor -> actor.entityId().equals(subject.getUniqueId()))
            .findFirst().orElse(null);
        if (lastVictim == null) {
            viewer.sendMessage("Target is missing from the final replay frame.");
            return true;
        }
        ReplayDeathMoment terminal = new ReplayDeathMoment(
            clip.attempt(), subject.getUniqueId(), clip.worldId(),
            clip.frames().getLast().tick(), System.currentTimeMillis(),
            lastVictim.x(), lastVictim.y(), lastVictim.z(),
            lastVictim.yaw(), lastVictim.pitch(), "Debug replay (no actual death)", "PREVIEW"
        );
        List<ReplayVisualEvent> visualEvents = combatRecorder.snapshotVisualFor(
            subject.getUniqueId(), clip.attempt(), clip.worldId(),
            clip.frames().getFirst().tick(), terminal.tick()
        );
        List<ReplayCreeperExplosionEvent> explosions = combatRecorder.snapshotExplosionsFor(
            subject.getUniqueId(), clip.attempt(), clip.worldId(),
            clip.frames().getFirst().tick(), terminal.tick()
        );
        ReplayActorScene scene = new ReplayActorScene(
            new FrozenDeathReplay(clip, terminal, List.of(), visualEvents, explosions)
        );
        boolean pov = args.length == 2 && args[1].equalsIgnoreCase("pov");
        finish(viewer.getUniqueId());
        try {
            Preview preview = new Preview(viewer, scene, clip, pov, visualEvents, explosions);
            active.put(viewer.getUniqueId(), preview);
            preview.start();
            viewer.sendMessage("Replaying recorded actors for 7 seconds (test only; no death/reset)."
                + (pov ? " POV camera enabled." : " Free spectator camera."));
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Unable to start debug replay preview.", exception);
            finish(viewer.getUniqueId());
            viewer.sendMessage("Replay preview could not start; check server logs.");
        }
        return true;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        finish(event.getPlayer().getUniqueId());
    }

    private void finish(UUID viewerId) {
        Preview preview = active.remove(viewerId);
        if (preview != null) {
            preview.close();
        }
    }

    @Override
    public void close() {
        for (UUID viewerId : List.copyOf(active.keySet())) {
            finish(viewerId);
        }
    }
}
