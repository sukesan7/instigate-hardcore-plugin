package dev.instigatehardcore.core;

import dev.instigatehardcore.countdown.CountdownManager;

import dev.instigatehardcore.player.PlayerResetManager;

import dev.instigatehardcore.replay.DeathReplayCaptureService;
import dev.instigatehardcore.replay.DeathReplayPlaybackService;
import dev.instigatehardcore.replay.FrozenDeathReplay;
import dev.instigatehardcore.replay.ReplayTiming;

import dev.instigatehardcore.stats.StatsManager;

import dev.instigatehardcore.telemetry.PlayerDeathRecord;
import dev.instigatehardcore.telemetry.PlayerTelemetryManager;

import dev.instigatehardcore.ui.InstigateTheme;

import dev.instigatehardcore.world.WorldRotationManager;

import net.kyori.adventure.text.Component;

import org.bukkit.Location;

import org.bukkit.command.CommandSender;

import org.bukkit.entity.Player;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;

import java.util.Objects;
import java.util.Optional;

public final class AttemptEndManager {

    private final JavaPlugin plugin;

    private final RunManager runManager;

    private final StatsManager statsManager;

    private final PlayerTelemetryManager telemetryManager;

    private final CountdownManager countdownManager;

    private final PlayerResetManager playerResetManager;

    private final WorldRotationManager worldRotationManager;

    /* Optional Phase 9B recorder; normal hardcore works without it. */
    private DeathReplayCaptureService replayCaptureService;
    private DeathReplayPlaybackService replayPlaybackService;
    private ReplayTiming replayTiming;

    public AttemptEndManager(
        JavaPlugin plugin,
        RunManager runManager,
        StatsManager statsManager,
        PlayerTelemetryManager telemetryManager,
        CountdownManager countdownManager,
        PlayerResetManager playerResetManager,
        WorldRotationManager worldRotationManager
    ) {
        this.plugin =
            Objects.requireNonNull(
                plugin
            );

        this.runManager =
            Objects.requireNonNull(
                runManager
            );

        this.statsManager =
            Objects.requireNonNull(
                statsManager
            );

        this.telemetryManager =
            Objects.requireNonNull(
                telemetryManager
            );

        this.countdownManager =
            Objects.requireNonNull(
                countdownManager
            );

        this.playerResetManager =
            Objects.requireNonNull(
                playerResetManager
            );

        this.worldRotationManager =
            Objects.requireNonNull(
                worldRotationManager
            );
    }

    /**
     * Connects the optional Phase 9B capture service after startup.
     * Phase 9D preview rendering remains independent of this flow.
     */
    public void setDeathReplayCaptureService(
        DeathReplayCaptureService service
    ) {
        this.replayCaptureService = Objects.requireNonNull(service);
    }

    /** Called only when Phase 9E is enabled and PacketEvents is loaded. */
    public void setDeathReplayPlaybackService(
        DeathReplayPlaybackService service,
        ReplayTiming timing
    ) {
        this.replayPlaybackService = Objects.requireNonNull(service);
        this.replayTiming = Objects.requireNonNull(timing);
    }

    /*
     * ------------------------------------------------------------
     * PLAYER DEATH
     * ------------------------------------------------------------
     */

    /**
     * Ends the current ACTIVE attempt because a player died.
     *
     * Only the first caller that successfully transitions
     * ACTIVE -> ENDING may end the attempt.
     */
    public synchronized boolean endFromPlayerDeath(
        Player player,
        String deathMessage,
        String deathCause
    ) {
        Objects.requireNonNull(
            player
        );

        Objects.requireNonNull(
            deathMessage
        );

        Objects.requireNonNull(
            deathCause
        );

        /*
         * Capture the exact death location before any respawn,
         * player reset, teleport or world transition can modify the
         * player's Bukkit location.
         *
         * Location includes the World reference, which means a
         * Nether death remains a Nether destination and an End
         * death remains an End destination.
         */
        Location deathLocation =
            player.getLocation()
                .clone();

        if (!beginEnding()) {
            return false;
        }

        int attempt =
            statsManager
                .getCurrentAttempt();

        /*
         * Freeze only the first confirmed death. This MUST happen
         * before player resets, death telemetry or spectator
         * teleports modify the victim and surrounding entities.
         *
         * Replay is observation-only in Phase 9B/9D. A capture
         * failure must never prevent the established death reset.
         */
        Optional<FrozenDeathReplay> frozenReplay = Optional.empty();
        if (replayCaptureService != null) {
            try {
                frozenReplay = replayCaptureService.freezeOnDeath(
                    player,
                    deathMessage,
                    deathCause
                );
            } catch (RuntimeException exception) {
                plugin.getLogger().log(
                    java.util.logging.Level.WARNING,
                    "Unable to freeze death replay; continuing normal reset.",
                    exception
                );
            }
        }

        endPlaytime(
            attempt
        );

        int totalDeaths =
            recordPlayerDeath(
                player,
                attempt,
                deathMessage,
                deathCause
            );

        announcePlayerDeath(
            player,
            attempt,
            totalDeaths,
            deathMessage
        );

        beginDeathSequence(
            attempt,
            deathLocation,
            frozenReplay
        );

        return true;
    }

    /*
     * ------------------------------------------------------------
     * ADMIN RESET
     * ------------------------------------------------------------
     */

    /**
     * Ends the current ACTIVE attempt administratively.
     *
     * Administrative resets do not record a player death and do
     * not have a death location to gather around.
     */
    public synchronized boolean endFromAdminReset(
        CommandSender sender
    ) {
        Objects.requireNonNull(
            sender
        );

        if (!beginEnding()) {
            return false;
        }

        /*
         * Administrative resets never create a replay clip.
         * Even a replay-cleanup failure must not interrupt rotation.
         */
        if (replayCaptureService != null) {
            try {
                replayCaptureService.clear();
            } catch (RuntimeException exception) {
                plugin.getLogger().log(
                    java.util.logging.Level.WARNING,
                    "Unable to clear replay capture during admin reset; continuing.",
                    exception
                );
            }
        }

        int attempt =
            statsManager
                .getCurrentAttempt();

        endPlaytime(
            attempt
        );

        announceAdminReset(
            sender,
            attempt
        );

        beginAdminCountdown(
            attempt
        );

        return true;
    }

    /*
     * ------------------------------------------------------------
     * STATE TRANSITION
     * ------------------------------------------------------------
     */

    private boolean beginEnding() {
        if (!runManager.isActive()) {
            return false;
        }

        return runManager
            .beginEnding();
    }

    /*
     * ------------------------------------------------------------
     * TELEMETRY
     * ------------------------------------------------------------
     */

    private void endPlaytime(
        int attempt
    ) {
        try {
            telemetryManager
                .endAttemptSessions(
                    attempt
                );
        } catch (
            IOException exception
        ) {
            plugin.getLogger().severe(
                "Failed to persist playtime when attempt #"
                    + attempt
                    + " ended."
            );

            exception.printStackTrace();
        }
    }

    private int recordPlayerDeath(
        Player player,
        int attempt,
        String deathMessage,
        String deathCause
    ) {
        int totalDeaths =
            statsManager
                .getDeaths(
                    player.getUniqueId()
                );

        try {
            statsManager.recordDeath(
                player.getUniqueId(),
                player.getName()
            );

            totalDeaths =
                statsManager
                    .getDeaths(
                        player.getUniqueId()
                    );
        } catch (
            IOException exception
        ) {
            plugin.getLogger().severe(
                "Failed to persist death statistics for "
                    + player.getName()
                    + "."
            );

            exception.printStackTrace();
        }

        try {
            telemetryManager
                .recordDeath(
                    player.getUniqueId(),
                    player.getName(),
                    new PlayerDeathRecord(
                        attempt,
                        System.currentTimeMillis(),
                        deathCause,
                        deathMessage
                    )
                );
        } catch (
            IOException exception
        ) {
            plugin.getLogger().severe(
                "Failed to persist structured death telemetry for "
                    + player.getName()
                    + "."
            );

            exception.printStackTrace();
        }

        return totalDeaths;
    }

    /*
     * ------------------------------------------------------------
     * COUNTDOWN
     * ------------------------------------------------------------
     */

    /**
     * Real player death:
     *
     * everyone becomes spectator and is gathered at the exact
     * death location before the reset countdown begins.
     */
    private void beginDeathSequence(
        int attempt,
        Location deathLocation,
        Optional<FrozenDeathReplay> frozenReplay
    ) {
        /*
         * Keep the established safe spectator transition first.
         * Packet-only ghosts are shown afterward in the exact
         * original dimension. The world will not rotate until
         * replay playback AND the final buffer have completed.
         */
        playerResetManager.beginCountdownPhase(deathLocation);

        if (replayPlaybackService != null
            && replayTiming != null
            && frozenReplay.isPresent()) {
            try {
                FrozenDeathReplay replay = frozenReplay.get();
                if (replay.clip().attempt() == attempt
                    && replayPlaybackService.play(
                        replay,
                        deathLocation,
                        replayTiming.replayTicks(),
                        (completed, playbackTicksElapsed) -> {
                            if (!runManager.isEnding()
                                || statsManager.getCurrentAttempt() != attempt) {
                                return;
                            }
                            int remaining = completed
                                ? replayTiming.bufferSeconds()
                                : replayTiming.remainingOnFailure(playbackTicksElapsed);
                            startCountdown(attempt, remaining);
                        }
                    )) {
                    plugin.getServer().broadcast(
                        InstigateTheme.chat(
                            InstigateTheme.secondary(
                                "Showing the final "
                                    + replayTiming.replaySeconds()
                                    + " seconds. Reset countdown follows."
                            )
                        )
                    );
                    return;
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().log(
                    java.util.logging.Level.WARNING,
                    "Shared death replay could not start; using normal countdown.",
                    exception
                );
            }
        }

        // Disabled, missing clip, missing PacketEvents, or startup failure.
        startCountdown(attempt);
    }

    /**
     * Administrative reset:
     *
     * there is no death location, so retain the existing generic
     * spectator countdown behavior.
     */
    private void beginAdminCountdown(
        int attempt
    ) {
        playerResetManager
            .beginCountdownPhase();

        startCountdown(
            attempt
        );
    }

    private void startCountdown(int attempt) {
        startCountdown(attempt, countdownManager.getDurationSeconds());
    }

    private void startCountdown(
        int attempt,
        int seconds
    ) {
        countdownManager
            .startCountdown(
                attempt,
                seconds,
                () -> {
                    if (
                        !runManager
                            .beginResetting()
                    ) {
                        plugin.getLogger().severe(
                            "[Instigate Cafe] "
                                + "Unable to transition attempt #"
                                + attempt
                                + " from ENDING to RESETTING."
                        );

                        return;
                    }

                    if (
                        !worldRotationManager
                            .rotateToStandby()
                    ) {
                        plugin.getLogger().severe(
                            "[Instigate Cafe] "
                                + "World rotation failed after attempt #"
                                + attempt
                                + "."
                        );
                    }
                }
            );
    }

    /*
     * ------------------------------------------------------------
     * CHAT ANNOUNCEMENTS
     * ------------------------------------------------------------
     */

    private void announcePlayerDeath(
        Player player,
        int attempt,
        int totalDeaths,
        String deathMessage
    ) {
        plugin.getServer()
            .broadcast(
                InstigateTheme.chat(
                    Component.text()
                        .append(
                            InstigateTheme.attempt(
                                attempt
                            )
                        )
                        .append(
                            InstigateTheme.secondary(
                                " has ended."
                            )
                        )
                        .build()
                )
            );

        plugin.getServer()
            .broadcast(
                InstigateTheme.chat(
                    Component.text(
                        deathMessage,
                        InstigateTheme.TEXT
                    )
                )
            );

        plugin.getServer()
            .broadcast(
                InstigateTheme.chat(
                    Component.text()
                        .append(
                            Component.text(
                                player.getName(),
                                InstigateTheme.TEXT
                            )
                        )
                        .append(
                            InstigateTheme.secondary(
                                " now has "
                                    + totalDeaths
                                    + " total death"
                                    + (
                                        totalDeaths == 1
                                            ? ""
                                            : "s"
                                    )
                                    + "."
                            )
                        )
                        .build()
                )
            );

        plugin.getServer()
            .broadcast(
                InstigateTheme.chat(
                    Component.text()
                        .append(
                            InstigateTheme.secondary(
                                "Next attempt in "
                            )
                        )
                        .append(
                            Component.text(
                                countdownManager
                                    .getDurationSeconds()
                                    + "s",
                                InstigateTheme.PURPLE
                            )
                        )
                        .append(
                            InstigateTheme.secondary(
                                "."
                            )
                        )
                        .build()
                )
            );
    }

    private void announceAdminReset(
        CommandSender sender,
        int attempt
    ) {
        plugin.getServer()
            .broadcast(
                InstigateTheme.chat(
                    Component.text()
                        .append(
                            InstigateTheme.attempt(
                                attempt
                            )
                        )
                        .append(
                            InstigateTheme.secondary(
                                " was reset by "
                            )
                        )
                        .append(
                            Component.text(
                                sender.getName(),
                                InstigateTheme.TEXT
                            )
                        )
                        .append(
                            InstigateTheme.secondary(
                                "."
                            )
                        )
                        .build()
                )
            );

        plugin.getServer()
            .broadcast(
                InstigateTheme.chat(
                    InstigateTheme.muted(
                        "No player death was recorded."
                    )
                )
            );

        plugin.getServer()
            .broadcast(
                InstigateTheme.chat(
                    Component.text()
                        .append(
                            InstigateTheme.secondary(
                                "Next attempt in "
                            )
                        )
                        .append(
                            Component.text(
                                countdownManager
                                    .getDurationSeconds()
                                    + "s",
                                InstigateTheme.PURPLE
                            )
                        )
                        .append(
                            InstigateTheme.secondary(
                                "."
                            )
                        )
                        .build()
                )
            );
    }
}