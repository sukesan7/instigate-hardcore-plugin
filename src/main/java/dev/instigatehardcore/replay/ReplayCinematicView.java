package dev.instigatehardcore.replay;

import dev.instigatehardcore.ui.InstigateTheme;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-spectator 9F.4 camera + vanilla-client cinematic HUD.
 * All calls are synchronous on Paper's server thread.
 *
 * Tracking uses a packet-only MARKER camera target, not continuous player
 * teleports. The client interpolates this marker smoothly while the actual
 * spectator stays in place server-side. No NMS or client mod is required.
 */
public final class ReplayCinematicView implements AutoCloseable {
    private final Player viewer;
    private final ReplayActorScene scene;
    private final UUID victimId;
    private final String victimName;
    private final boolean tracking;
    private final boolean collisionCheck;
    private final boolean hideScoreboard;
    private final double followDistance;
    private final double cameraHeight;
    private final double shoulderOffset;
    private final double smoothing;
    private ReplayPacketCinematicCamera packetCamera;
    private final BossBar bossBar;
    private Scoreboard previousScoreboard;
    private Scoreboard emptyScoreboard;
    private ReplayCinematicCameraPath.Frame lastCamera;
    private boolean closed;

    public ReplayCinematicView(
        JavaPlugin plugin, Player viewer, ReplayActorScene scene,
        boolean enableTracking
    ) {
        Objects.requireNonNull(plugin);
        this.viewer = Objects.requireNonNull(viewer);
        this.scene = Objects.requireNonNull(scene);
        this.victimId = scene.death().victimId();
        ReplayActorPose victim = scene.at(0).get(victimId);
        if (victim == null) throw new IllegalArgumentException("Replay has no victim pose.");
        this.victimName = ReplayCinematicHudText.cleanName(victim.name());

        var config = plugin.getConfig();
        this.tracking = enableTracking && config.getBoolean(
            "death-replay.camera.tracking-enabled", true);
        this.collisionCheck = config.getBoolean("death-replay.camera.collision-check", true);
        this.followDistance = clamp(config.getDouble("death-replay.camera.follow-distance", 4.0), 2.0, 8.0);
        this.cameraHeight = clamp(config.getDouble("death-replay.camera.height", 2.2), 1.0, 5.0);
        this.shoulderOffset = clamp(config.getDouble("death-replay.camera.shoulder-offset", 0.8), -2.0, 2.0);
        this.smoothing = clamp(config.getDouble("death-replay.camera.smoothing", 0.25), 0.08, 0.8);
        // The old update-interval-ticks option caused visible camera stepping.
        // Cinematic camera targets must update every single server tick.
        this.hideScoreboard = config.getBoolean("death-replay.cinematic.hide-scoreboard", true);
        this.bossBar = BossBar.bossBar(
            Component.text("INSTIGATE CAFE  |  DEATHCAM: " + victimName, InstigateTheme.TEXT),
            1.0f, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS
        );

        // Treat HUD startup as a transaction; failed initialization must
        // never leave the scoreboard or boss bar attached to a player.
        try {
            if (hideScoreboard && Bukkit.getScoreboardManager() != null) {
                previousScoreboard = viewer.getScoreboard();
                emptyScoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
                viewer.setScoreboard(emptyScoreboard);
            }
            viewer.showBossBar(bossBar);
            if (tracking) {
                ReplayCinematicCameraPath.Frame initial = cameraForVictim(victim);
                lastCamera = initial;
                packetCamera = new ReplayPacketCinematicCamera(
                    viewer, scene.worldId(), initial
                );
            }
            viewer.showTitle(Title.title(
                Component.text("DEATHCAM", InstigateTheme.PURPLE)
                    .decorate(TextDecoration.BOLD),
                Component.text(victimName, InstigateTheme.TEXT),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(650), Duration.ofMillis(250))
            ));
        } catch (RuntimeException exception) {
            close();
            throw exception;
        }
    }

    public void renderTick(int playbackTick) {
        if (closed) return;
        if (tracking) {
            trackVictim(playbackTick);
        }
        if (playbackTick % 2 == 0) {
            bossBar.progress(ReplayCinematicHudText.fractionRemaining(
                playbackTick, scene.durationTicks()));
        }
        if (playbackTick % 5 == 0) {
            viewer.sendActionBar(Component.text()
                .append(Component.text("DEATHCAM: ", InstigateTheme.PURPLE)
                    .decorate(TextDecoration.BOLD))
                .append(Component.text(victimName, InstigateTheme.TEXT))
                .append(Component.text("   •   ", InstigateTheme.MUTED))
                .append(Component.text(
                    ReplayCinematicHudText.clock(playbackTick, scene.durationTicks()),
                    InstigateTheme.AZURE
                ))
                .build());
        }
    }

    private void trackVictim(int tick) {
        ReplayActorPose victim = scene.at(tick).get(victimId);
        if (victim == null) return;
        ReplayCinematicCameraPath.Frame desired = cameraForVictim(victim);
        lastCamera = ReplayCinematicCameraPath.smooth(lastCamera, desired, smoothing);
        // Smoothing can put the camera back through a wall after the desired
        // position was collision-clipped. Verify the final camera too.
        if (collisionCheck) lastCamera = avoidWall(lastCamera, victim);
        if (packetCamera != null) packetCamera.moveTo(lastCamera);
    }

    private ReplayCinematicCameraPath.Frame cameraForVictim(ReplayActorPose victim) {
        ReplayCinematicCameraPath.Frame desired = ReplayCinematicCameraPath.desired(
            victim.x(), victim.y(), victim.z(), victim.yaw(),
            followDistance, cameraHeight, shoulderOffset
        );
        return collisionCheck ? avoidWall(desired, victim) : desired;
    }

    private ReplayCinematicCameraPath.Frame avoidWall(
        ReplayCinematicCameraPath.Frame desired, ReplayActorPose victim
    ) {
        Location focus = new Location(viewer.getWorld(),
            victim.x(), victim.y() + 1.28, victim.z());
        Vector towardCamera = new Vector(
            desired.x() - focus.getX(), desired.y() - focus.getY(),
            desired.z() - focus.getZ());
        double length = towardCamera.length();
        if (length < 0.01) return desired;
        Vector direction = towardCamera.multiply(1.0 / length);
        RayTraceResult hit = viewer.getWorld().rayTraceBlocks(
            focus, direction, length, FluidCollisionMode.NEVER, true);
        if (hit == null || hit.getHitPosition() == null) return desired;
        Vector safe = hit.getHitPosition().subtract(direction.clone().multiply(0.35));
        // When the target is pressed against a wall, keep the camera slightly
        // separated instead of teleporting inside the victim or the wall.
        if (safe.distanceSquared(focus.toVector()) < 0.4 * 0.4) {
            safe = focus.toVector().add(direction.multiply(0.4));
        }
        return ReplayCinematicCameraPath.lookAt(safe.getX(), safe.getY(), safe.getZ(),
            focus.getX(), focus.getY(), focus.getZ());
    }

    private static double clamp(double value, double low, double high) {
        if (!Double.isFinite(value)) return low;
        return Math.max(low, Math.min(high, value));
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        // Always restore the client camera first, even if scoreboard/HUD
        // cleanup fails. In particular this runs on replay interruption.
        if (packetCamera != null) {
            try { packetCamera.close(); } catch (RuntimeException ignored) { }
            packetCamera = null;
        }
        if (!viewer.isOnline()) return;
        try {
            viewer.hideBossBar(bossBar);
        } catch (RuntimeException ignored) {
            // Do not strand hidden ghosts merely because a HUD update fails.
        }
        try {
            viewer.clearTitle();
            viewer.sendActionBar(Component.empty());
        } catch (RuntimeException ignored) {
            // The next reset countdown will replace the HUD regardless.
        }
        if (emptyScoreboard != null && previousScoreboard != null) {
            try {
                if (viewer.getScoreboard() == emptyScoreboard) {
                    viewer.setScoreboard(previousScoreboard);
                }
            } catch (RuntimeException ignored) {
                // A different plugin may have changed the scoreboard meanwhile.
            }
        }
    }
}
