package dev.instigatehardcore.replay;

import dev.instigatehardcore.ui.InstigateTheme;

import net.kyori.adventure.text.Component;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.logging.Level;

/**
 * Shared, packet-only 9E death playback for Java clients.
 *
 * All Bukkit and PacketEvents interaction runs on the primary server thread.
 * The engine never spawns real entities or changes the recorded world.
 *
 * Completion callback: (completed, playbackTicksElapsed).
 * Both successful completion and runtime failure notify the caller once,
 * allowing the caller to start an appropriate final reset countdown.
 */
public final class DeathReplayPlaybackService implements AutoCloseable {

    private static final long RESPAWN_SETTLE_TICKS = 4L;
    private static final double CAMERA_HORIZONTAL_OFFSET = 3.5;
    private static final double CAMERA_VERTICAL_OFFSET = 1.8;

    private final JavaPlugin plugin;
    private final Runnable stopDeveloperPreviews;
    private Session active;

    public DeathReplayPlaybackService(
        JavaPlugin plugin,
        Runnable stopDeveloperPreviews
    ) {
        this.plugin = Objects.requireNonNull(plugin);
        this.stopDeveloperPreviews = Objects.requireNonNull(stopDeveloperPreviews);
    }

    /**
     * Starts one shared replay; returns false without invoking the callback
     * when a clip is invalid or no eligible viewer is online.
     * Caller may then use its original ten-second death countdown.
     */
    public boolean play(
        FrozenDeathReplay frozen,
        Location deathLocation,
        int durationTicks,
        BiConsumer<Boolean, Integer> onFinish
    ) {
        assertMainThread();
        Objects.requireNonNull(frozen);
        Objects.requireNonNull(deathLocation);
        Objects.requireNonNull(onFinish);

        if (active != null || durationTicks < 1 || durationTicks > 600
            || deathLocation.getWorld() == null
            || !deathLocation.getWorld().getUID().equals(frozen.clip().worldId())) {
            return false;
        }

        World world = Bukkit.getWorld(frozen.clip().worldId());
        if (world == null) {
            return false;
        }

        // The spectator reset runs immediately before play(), so the victim
        // may still be in PlayerDeathEvent. Check world and online state only.
        boolean hasViewer = Bukkit.getOnlinePlayers().stream().anyMatch(
            viewer -> viewer.getWorld().getUID().equals(world.getUID())
                || viewer.isDead()
        );
        if (!hasViewer) {
            return false;
        }

        ReplayActorScene scene;
        try {
            scene = new ReplayActorScene(frozen, durationTicks);
            if (!scene.at(0).containsKey(frozen.death().victimId())) {
                plugin.getLogger().warning(
                    "Death replay has no victim actor; falling back to normal countdown."
                );
                return false;
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Invalid death replay scene.", exception);
            return false;
        }

        Session session = new Session(scene, frozen.clip(), frozen.visualEvents(),
            frozen.creeperExplosions(), deathLocation.clone(), onFinish);
        active = session;
        try {
            stopDeveloperPreviews.run();
            session.warmupTask = Bukkit.getScheduler().runTaskLater(
                plugin, session::beginAfterRespawn, RESPAWN_SETTLE_TICKS
            );
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Unable to schedule shared death replay.", exception);
            session.shutdown(false);
            active = null;
            return false;
        }
        return true;
    }

    public boolean isPlaying() {
        return active != null;
    }

    private final class Session {
        final ReplayActorScene scene;
        final ReplayClip clip;
        final List<ReplayVisualEvent> visualEvents;
        final List<ReplayCreeperExplosionEvent> explosions;
        final Location deathLocation;
        final BiConsumer<Boolean, Integer> callback;
        final Map<UUID, Viewer> viewers = new HashMap<>();
        BukkitTask warmupTask;
        BukkitTask frameTask;
        int tick;
        boolean finished;

        Session(
            ReplayActorScene scene,
            ReplayClip clip,
            List<ReplayVisualEvent> visualEvents,
            List<ReplayCreeperExplosionEvent> explosions,
            Location deathLocation,
            BiConsumer<Boolean, Integer> callback
        ) {
            this.scene = scene;
            this.clip = clip;
            this.visualEvents = List.copyOf(visualEvents);
            this.explosions = List.copyOf(explosions);
            this.deathLocation = deathLocation;
            this.callback = callback;
        }

        void beginAfterRespawn() {
            if (finished || active != this) {
                return;
            }
            try {
                for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
                    if (!player.isOnline() || player.isDead()
                        || player.getGameMode() != GameMode.SPECTATOR) {
                        continue;
                    }
                    // Only the original dimension can display the scene.
                    if (!player.getWorld().getUID().equals(scene.worldId())) {
                        continue;
                    }
                    try {
                        Viewer viewer = new Viewer(player);
                        viewers.put(player.getUniqueId(), viewer);
                    } catch (RuntimeException exception) {
                        // A failed viewer setup may already have teleported the
                        // player. Always return them to the original death site.
                        try {
                            if (player.isOnline() && player.getGameMode() == GameMode.SPECTATOR
                                && player.getWorld().getUID().equals(scene.worldId())) {
                                player.teleport(deathLocation);
                            }
                        } catch (RuntimeException ignored) {
                            // Keep processing other viewers.
                        }
                        plugin.getLogger().log(
                            Level.WARNING,
                            "Could not attach death replay viewer " + player.getName(),
                            exception
                        );
                    }
                }
                if (viewers.isEmpty()) {
                    complete(false);
                    return;
                }
                frameTask = Bukkit.getScheduler().runTaskTimer(
                    plugin, this::frame, 0L, 1L
                );
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Death replay setup failed.", exception);
                complete(false);
            }
        }

        void frame() {
            if (finished) {
                return;
            }
            for (var iterator = viewers.entrySet().iterator(); iterator.hasNext();) {
                var entry = iterator.next();
                Viewer viewer = entry.getValue();
                if (!viewer.player.isOnline() || viewer.player.isDead()
                    || viewer.player.getGameMode() != GameMode.SPECTATOR
                    || !viewer.player.getWorld().getUID().equals(scene.worldId())) {
                    viewer.close();
                    iterator.remove();
                }
            }
            if (viewers.isEmpty()) {
                complete(false);
                return;
            }
            try {
                for (var iterator = viewers.entrySet().iterator(); iterator.hasNext();) {
                    Viewer viewer = iterator.next().getValue();
                    try {
                        viewer.playback.renderTick(tick);
                        if (tick % 20 == 0) {
                            int left = Math.max(0, (scene.durationTicks() - tick + 19) / 20);
                            viewer.player.sendActionBar(
                                InstigateTheme.chat(
                                    Component.text("INSTANT REPLAY  ", InstigateTheme.PURPLE)
                                        .append(Component.text(left + "s", InstigateTheme.AZURE))
                                )
                            );
                        }
                    } catch (RuntimeException viewerFailure) {
                        plugin.getLogger().log(
                            Level.WARNING,
                            "Death replay failed for viewer " + viewer.player.getName(),
                            viewerFailure
                        );
                        viewer.close();
                        iterator.remove();
                    }
                }
                if (viewers.isEmpty()) {
                    complete(false);
                } else if (tick >= scene.durationTicks()) {
                    complete(true);
                } else {
                    tick++;
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Death replay tick failed.", exception);
                complete(false);
            }
        }

        void complete(boolean success) {
            if (finished) {
                return;
            }
            int playedTicks = tick;
            shutdown(true);
            active = null;
            try {
                callback.accept(success, playedTicks);
            } catch (RuntimeException exception) {
                plugin.getLogger().log(
                    Level.SEVERE,
                    "Death replay completion callback failed; check reset lifecycle.",
                    exception
                );
            }
        }

        void shutdown(boolean restoreViewersToDeath) {
            if (finished) {
                return;
            }
            finished = true;
            if (warmupTask != null) {
                warmupTask.cancel();
                warmupTask = null;
            }
            if (frameTask != null) {
                frameTask.cancel();
                frameTask = null;
            }
            for (Viewer viewer : List.copyOf(viewers.values())) {
                viewer.close();
                if (restoreViewersToDeath && viewer.player.isOnline()
                    && viewer.player.getGameMode() == GameMode.SPECTATOR
                    && viewer.player.getWorld().getUID().equals(scene.worldId())) {
                    try {
                        viewer.player.teleport(deathLocation);
                    } catch (RuntimeException exception) {
                        plugin.getLogger().log(
                            Level.WARNING, "Could not restore death spectator position.", exception
                        );
                    }
                }
            }
            viewers.clear();
        }

        final class Viewer implements AutoCloseable {
            final Player player;
            final List<Entity> hiddenActors = new ArrayList<>();
            final ReplayPacketActorTransport transport;
            final ReplayActorPlayback playback;
            boolean closed;

            Viewer(Player player) {
                this.player = player;
                ReplayActorPose start = scene.at(0).get(scene.death().victimId());
                if (start == null) {
                    throw new IllegalArgumentException("Replay missing victim's first recorded pose.");
                }
                Location nearStart = new Location(
                    player.getWorld(),
                    start.x() + CAMERA_HORIZONTAL_OFFSET,
                    start.y() + CAMERA_VERTICAL_OFFSET,
                    start.z() + CAMERA_HORIZONTAL_OFFSET
                );
                Vector toVictim = new Vector(
                    start.x() - nearStart.getX(),
                    (start.y() + 1.2) - nearStart.getY(),
                    start.z() - nearStart.getZ()
                );
                if (toVictim.lengthSquared() > 0.0001) {
                    nearStart.setDirection(toVictim);
                }
                if (!player.teleport(nearStart)) {
                    throw new IllegalStateException("Unable to position spectator near replay start.");
                }

                // Treat hiding + packet transport as one transaction:
                // a failed setup must not strand invisible live entities.
                ReplayPacketActorTransport newTransport = null;
                try {
                    Set<UUID> actorIds = new HashSet<>();
                    for (ReplayFrame frame : clip.frames()) {
                        for (ReplayActorSnapshot actor : frame.actors()) {
                            actorIds.add(actor.entityId());
                        }
                    }
                    for (UUID actorId : actorIds) {
                        Entity live = Bukkit.getEntity(actorId);
                        if (live != null && !live.equals(player)
                            && live.getWorld().getUID().equals(scene.worldId())) {
                            player.hideEntity(plugin, live);
                            hiddenActors.add(live);
                        }
                    }
                    newTransport = new ReplayPacketActorTransport(player, scene.worldId());
                    this.playback = new ReplayActorPlayback(
                        scene, newTransport, visualEvents, explosions,
                        plugin.getConfig().getBoolean("death-replay.visuals.movement-particles", true),
                        plugin.getConfig().getInt("death-replay.visuals.max-particles-per-tick", 24)
                    );
                    this.transport = newTransport;
                } catch (RuntimeException exception) {
                    if (newTransport != null) {
                        try {
                            newTransport.close();
                        } catch (RuntimeException ignored) {
                        }
                    }
                    for (Entity entity : hiddenActors) {
                        if (player.isOnline()) {
                            try {
                                player.showEntity(plugin, entity);
                            } catch (RuntimeException ignored) {
                            }
                        }
                    }
                    hiddenActors.clear();
                    throw exception;
                }
            }

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                closed = true;
                try {
                    playback.close();
                } catch (RuntimeException exception) {
                    plugin.getLogger().log(
                        Level.WARNING, "Could not fully remove packet replay actors.", exception
                    );
                } finally {
                    if (player.isOnline()) {
                        for (Entity entity : hiddenActors) {
                            try {
                                player.showEntity(plugin, entity);
                            } catch (RuntimeException ignored) {
                                // Entity may have despawned since it was hidden.
                            }
                        }
                    }
                    hiddenActors.clear();
                }
            }
        }
    }

    @Override
    public void close() {
        assertMainThread();
        if (active != null) {
            Session session = active;
            active = null;
            session.shutdown(false);
        }
    }

    private static void assertMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Death replay must run on the server main thread.");
        }
    }
}
