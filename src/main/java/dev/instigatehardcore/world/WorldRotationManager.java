package dev.instigatehardcore.world;

import dev.instigatehardcore.core.RunManager;
import dev.instigatehardcore.participation.AttemptParticipantManager;
import dev.instigatehardcore.player.PlayerResetManager;
import dev.instigatehardcore.scoreboard.HardcoreScoreboardManager;
import dev.instigatehardcore.stats.StatsManager;
import dev.instigatehardcore.telemetry.PlayerTelemetryManager;
import dev.instigatehardcore.ui.InstigateTheme;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

public final class WorldRotationManager {

    private static final long STANDBY_GENERATION_DELAY_TICKS =
        40L;

    private final JavaPlugin plugin;

    private final RunManager runManager;
    private final StatsManager statsManager;

    private final PlayerResetManager playerResetManager;

    private final WorldSetManager worldSetManager;
    private final WorldCleanupManager worldCleanupManager;

    private final AttemptParticipantManager participantManager;
    private final PlayerTelemetryManager telemetryManager;

    private final HardcoreScoreboardManager scoreboardManager;

    private boolean rotationInProgress;

    public WorldRotationManager(
        JavaPlugin plugin,
        RunManager runManager,
        StatsManager statsManager,
        PlayerResetManager playerResetManager,
        WorldSetManager worldSetManager,
        WorldCleanupManager worldCleanupManager,
        AttemptParticipantManager participantManager,
        PlayerTelemetryManager telemetryManager,
        HardcoreScoreboardManager scoreboardManager
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

        this.playerResetManager =
            Objects.requireNonNull(
                playerResetManager
            );

        this.worldSetManager =
            Objects.requireNonNull(
                worldSetManager
            );

        this.worldCleanupManager =
            Objects.requireNonNull(
                worldCleanupManager
            );

        this.participantManager =
            Objects.requireNonNull(
                participantManager
            );

        this.telemetryManager =
            Objects.requireNonNull(
                telemetryManager
            );

        /*
         * May be null when scoreboard.enabled=false.
         */
        this.scoreboardManager =
            scoreboardManager;
    }

    public synchronized boolean rotateToStandby() {
        if (rotationInProgress) {
            plugin.getLogger().warning(
                "[Instigate Cafe] "
                    + "World rotation is already in progress."
            );

            return false;
        }

        if (!runManager.isResetting()) {
            plugin.getLogger().severe(
                "[Instigate Cafe] "
                    + "World rotation requested while run state is "
                    + runManager.getState()
                    + "."
            );

            return false;
        }

        WorldSet standby =
            worldSetManager
                .getStandbyWorldSet();

        if (standby == null) {
            plugin.getLogger().severe(
                "[Instigate Cafe] "
                    + "No standby WorldSet is available."
            );

            moveEveryoneToSafety();

            return false;
        }

        int expectedAttempt =
            statsManager
                .getCurrentAttempt()
                + 1;

        if (
            standby.attemptNumber()
                != expectedAttempt
        ) {
            plugin.getLogger().severe(
                "[Instigate Cafe] "
                    + "Standby attempt mismatch. Expected #"
                    + expectedAttempt
                    + " but found #"
                    + standby.attemptNumber()
                    + "."
            );

            moveEveryoneToSafety();

            return false;
        }

        rotationInProgress =
            true;

        try {
            performRotation(
                standby
            );

            return true;
        } catch (
            Exception exception
        ) {
            plugin.getLogger().severe(
                "[Instigate Cafe] "
                    + "Seamless world rotation failed."
            );

            exception.printStackTrace();

            moveEveryoneToSafety();

            return false;
        } finally {
            rotationInProgress =
                false;
        }
    }

    private void performRotation(
        WorldSet standby
    ) throws IOException {

        int oldAttempt =
            statsManager
                .getCurrentAttempt();

        int newAttempt =
            standby
                .attemptNumber();

        Location newSpawn =
            standby
                .getSpawnLocation();

        plugin.getLogger().info(
            "[Instigate Cafe] "
                + "Rotating attempt #"
                + oldAttempt
                + " -> #"
                + newAttempt
                + "."
        );

        /*
         * Death/admin reset normally closes these sessions when
         * ACTIVE -> ENDING occurs.
         *
         * This is kept as a defensive safeguard.
         */
        try {
            telemetryManager
                .endAttemptSessions(
                    oldAttempt
                );
        } catch (
            IOException exception
        ) {
            plugin.getLogger().severe(
                "[Instigate Cafe] "
                    + "Failed to finalize telemetry for attempt #"
                    + oldAttempt
                    + " before rotation."
            );

            exception.printStackTrace();
        }

        /*
         * Persist ROTATING before moving the first player.
         *
         * If Paper stops after this point, Phase 6 recovery will
         * finish promotion on the next startup.
         */
        worldSetManager
            .beginRotation();

        /*
         * The standby has been frozen at a deterministic morning
         * while waiting. Reassert that state and resume normal
         * time/weather progression before moving the first player.
         */
        worldSetManager
            .prepareStandbyForActivation();

        /*
         * Make sure the incoming attempt exists in persistent
         * participation history before players are transferred.
         */
        participantManager
            .ensureAttempt(
                newAttempt
            );

        /*
         * Transfer every currently connected player into the
         * prepared standby attempt.
         *
         * Participation is persisted immediately.
         *
         * Telemetry does NOT begin here because RunManager is
         * still RESETTING.
         */
        for (
            Player player :
            plugin.getServer()
                .getOnlinePlayers()
        ) {
            playerResetManager
                .prepareForNewAttempt(
                    player,
                    newSpawn
                );

            participantManager
                .recordParticipant(
                    newAttempt,
                    player.getUniqueId(),
                    player.getName()
                );
        }

        /*
         * Promote the standby world to ACTIVE and persist the new
         * STABLE world-state metadata.
         */
        WorldSet retiredWorldSet =
            worldSetManager
                .promoteStandby();

        /*
         * Campaign attempt number advances only after the new
         * world has successfully become authoritative.
         */
        int advancedAttempt =
            statsManager
                .advanceAttempt();

        if (
            advancedAttempt
                != newAttempt
        ) {
            throw new IOException(
                "StatsManager advanced to attempt #"
                    + advancedAttempt
                    + " but world rotation activated attempt #"
                    + newAttempt
                    + "."
            );
        }

        /*
         * RESETTING -> ACTIVE.
         */
        if (
            !runManager
                .beginNextRun()
        ) {
            throw new IllegalStateException(
                "Unable to transition new attempt to ACTIVE."
            );
        }

        /*
         * Gameplay time for the new attempt begins only after the
         * new attempt is actually ACTIVE.
         */
        for (
            Player player :
            plugin.getServer()
                .getOnlinePlayers()
        ) {
            if (
                worldSetManager
                    .isActiveWorld(
                        player.getWorld()
                    )
            ) {
                telemetryManager
                    .beginSession(
                        newAttempt,
                        player.getUniqueId(),
                        player.getName()
                    );
            }
        }

        if (scoreboardManager != null) {
            scoreboardManager
                .refresh();
        }

        announceNewAttempt(
            newAttempt
        );

        plugin.getLogger().info(
            "[Instigate Cafe] "
                + "Attempt #"
                + newAttempt
                + " is now ACTIVE with "
                + participantManager
                    .getParticipantCount(
                        newAttempt
                    )
                + " participant(s)."
        );

        /*
         * The old ACTIVE set can now be safely unloaded and
         * deleted.
         */
        boolean cleanupScheduled =
            worldCleanupManager
                .scheduleCleanup(
                    retiredWorldSet
                );

        if (!cleanupScheduled) {
            plugin.getLogger().severe(
                "[Instigate Cafe] "
                    + "Retired world cleanup could not be scheduled."
            );
        }

        /*
         * Give the newly-active attempt a moment to settle before
         * generating the next standby WorldSet.
         */
        scheduleReplacementStandby();
    }

    /*
     * ------------------------------------------------------------
     * STANDBY GENERATION
     * ------------------------------------------------------------
     */

    private void scheduleReplacementStandby() {
        plugin.getServer()
            .getScheduler()
            .runTaskLater(
                plugin,
                () -> {
                    try {
                        worldSetManager
                            .createReplacementStandby();

                        plugin.getLogger().info(
                            "[Instigate Cafe] "
                                + "Replacement standby world is ready."
                        );
                    } catch (
                        IOException exception
                    ) {
                        plugin.getLogger().severe(
                            "[Instigate Cafe] "
                                + "Failed to create replacement "
                                + "standby WorldSet."
                        );

                        exception.printStackTrace();

                        plugin.getServer().broadcast(
                            InstigateTheme.chat(
                                InstigateTheme.error(
                                    "The next standby world could not be prepared."
                                )
                            )
                        );
                    }
                },
                STANDBY_GENERATION_DELAY_TICKS
            );
    }

    /*
     * ------------------------------------------------------------
     * NEW ATTEMPT UI
     * ------------------------------------------------------------
     */

    private void announceNewAttempt(
        int attemptNumber
    ) {
        /*
         * Titles do not use the chat prefix.
         *
         * Azure brand + purple attempt number keeps the visual
         * hierarchy simple.
         */
        Component titleText =
            InstigateTheme
                .brand();

        Component subtitleText =
            Component.text(
                "Attempt #"
                    + attemptNumber,
                InstigateTheme.PURPLE
            );

        Title title =
            Title.title(
                titleText,
                subtitleText,
                Title.Times.times(
                    Duration.ofMillis(
                        250
                    ),
                    Duration.ofSeconds(
                        3
                    ),
                    Duration.ofMillis(
                        750
                    )
                )
            );

        /*
         * Every plugin-originated ordinary chat message uses the
         * [Instigate Cafe] prefix.
         */
        Component chatMessage =
            InstigateTheme.chat(
                Component.text()
                    .append(
                        InstigateTheme.attempt(
                            attemptNumber
                        )
                    )
                    .append(
                        InstigateTheme.secondary(
                            " has begun. "
                        )
                    )
                    .append(
                        Component.text(
                            "good luck.",
                            InstigateTheme.TEXT
                        )
                    )
                    .build()
            );

        for (
            Player player :
            plugin.getServer()
                .getOnlinePlayers()
        ) {
            player.showTitle(
                title
            );

            player.sendMessage(
                chatMessage
            );
        }
    }

    /*
     * ------------------------------------------------------------
     * EMERGENCY SAFETY
     * ------------------------------------------------------------
     */

    private void moveEveryoneToSafety() {
        Location lobbySpawn =
            worldSetManager
                .getLobbyWorld()
                .getSpawnLocation()
                .clone()
                .add(
                    0.5,
                    0.0,
                    0.5
                );

        for (
            Player player :
            plugin.getServer()
                .getOnlinePlayers()
        ) {
            /*
             * A failed rotation must never leave ACTIVE playtime
             * accumulating.
             */
            try {
                telemetryManager
                    .endSession(
                        player.getUniqueId()
                    );
            } catch (
                IOException exception
            ) {
                plugin.getLogger().severe(
                    "[Instigate Cafe] "
                        + "Unable to close telemetry session for "
                        + player.getName()
                        + " during emergency recovery."
                );

                exception.printStackTrace();
            }

            try {
                playerResetManager
                    .prepareForCountdown(
                        player
                    );

                /*
                 * Schedule the actual lobby teleport onto the
                 * normal server task queue.
                 */
                plugin.getServer()
                    .getScheduler()
                    .runTask(
                        plugin,
                        () -> {
                            if (!player.isOnline()) {
                                return;
                            }

                            boolean teleported =
                                player.teleport(
                                    lobbySpawn
                                );

                            if (!teleported) {
                                plugin.getLogger().severe(
                                    "[Instigate Cafe] "
                                        + "Unable to teleport "
                                        + player.getName()
                                        + " to the safety lobby."
                                );

                                return;
                            }

                            player.setGameMode(
                                GameMode.SPECTATOR
                            );
                        }
                    );
            } catch (
                Exception exception
            ) {
                plugin.getLogger().severe(
                    "[Instigate Cafe] "
                        + "Unable to move "
                        + player.getName()
                        + " to the safety lobby."
                );

                exception.printStackTrace();
            }
        }

        /*
         * This is a genuine failure condition, so the theme's
         * soft-red ERROR colour is appropriate here.
         */
        plugin.getServer().broadcast(
            InstigateTheme.chat(
                InstigateTheme.error(
                    "World rotation failed. Players were moved to the "
                        + "safety lobby. Restart the server to recover "
                        + "the world pipeline."
                )
            )
        );
    }

    /*
     * ------------------------------------------------------------
     * DIAGNOSTICS
     * ------------------------------------------------------------
     */

    public synchronized boolean isRotationInProgress() {
        return rotationInProgress;
    }
}