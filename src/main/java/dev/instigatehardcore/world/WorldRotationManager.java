package dev.instigatehardcore.world;

import dev.instigatehardcore.core.RunManager;
import dev.instigatehardcore.participation.AttemptParticipantManager;
import dev.instigatehardcore.player.PlayerResetManager;
import dev.instigatehardcore.scoreboard.HardcoreScoreboardManager;
import dev.instigatehardcore.stats.StatsManager;
import dev.instigatehardcore.telemetry.PlayerTelemetryManager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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
            Objects.requireNonNull(plugin);

        this.runManager =
            Objects.requireNonNull(runManager);

        this.statsManager =
            Objects.requireNonNull(statsManager);

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
                "[Instigate Cafe Hardcore] "
                    + "World rotation is already in progress."
            );

            return false;
        }

        if (!runManager.isResetting()) {
            plugin.getLogger().severe(
                "[Instigate Cafe Hardcore] "
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
                "[Instigate Cafe Hardcore] "
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
                "[Instigate Cafe Hardcore] "
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
                "[Instigate Cafe Hardcore] "
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
            standby.attemptNumber();

        Location newSpawn =
            standby.getSpawnLocation();

        plugin.getLogger().info(
            "[Instigate Cafe Hardcore] "
                + "Rotating attempt #"
                + oldAttempt
                + " -> #"
                + newAttempt
                + "."
        );

        /*
         * Defensive safeguard. DeathListener normally closes these
         * immediately when ACTIVE -> ENDING occurs.
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
                "Failed to finalize telemetry for attempt #"
                    + oldAttempt
                    + " before rotation."
            );

            exception.printStackTrace();
        }

        /*
         * Persist ROTATING before moving the first player.
         */
        worldSetManager
            .beginRotation();

        participantManager
            .ensureAttempt(
                newAttempt
            );

        /*
         * Move everyone currently connected into the prepared
         * standby attempt.
         *
         * Participation is persisted immediately. Playtime does
         * not begin yet because RunManager is still RESETTING.
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

        WorldSet retiredWorldSet =
            worldSetManager
                .promoteStandby();

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
        if (!runManager.beginNextRun()) {
            throw new IllegalStateException(
                "Unable to transition new attempt to ACTIVE."
            );
        }

        /*
         * Only now does gameplay time for the new attempt begin.
         */
        for (
            Player player :
            plugin.getServer()
                .getOnlinePlayers()
        ) {
            if (
                worldSetManager.isActiveWorld(
                    player.getWorld()
                )
            ) {
                telemetryManager.beginSession(
                    newAttempt,
                    player.getUniqueId(),
                    player.getName()
                );
            }
        }

        if (scoreboardManager != null) {
            scoreboardManager.refresh();
        }

        announceNewAttempt(
            newAttempt
        );

        plugin.getLogger().info(
            "[Instigate Cafe Hardcore] "
                + "Attempt #"
                + newAttempt
                + " is now ACTIVE with "
                + participantManager
                    .getParticipantCount(
                        newAttempt
                    )
                + " participant(s)."
        );

        boolean cleanupScheduled =
            worldCleanupManager
                .scheduleCleanup(
                    retiredWorldSet
                );

        if (!cleanupScheduled) {
            plugin.getLogger().severe(
                "[Instigate Cafe Hardcore] "
                    + "Retired world cleanup could not be scheduled."
            );
        }

        scheduleReplacementStandby();
    }

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
                            "[Instigate Cafe Hardcore] "
                                + "Replacement standby world is ready."
                        );
                    } catch (
                        IOException exception
                    ) {
                        plugin.getLogger().severe(
                            "[Instigate Cafe Hardcore] "
                                + "Failed to create replacement "
                                + "standby WorldSet."
                        );

                        exception.printStackTrace();

                        plugin.getServer().broadcast(
                            Component.text(
                                "[Instigate Cafe Hardcore] "
                                    + "Warning: the next standby "
                                    + "world could not be prepared.",
                                NamedTextColor.RED
                            )
                        );
                    }
                },
                STANDBY_GENERATION_DELAY_TICKS
            );
    }

    private void announceNewAttempt(
        int attemptNumber
    ) {
        Component titleText =
            Component.text(
                "INSTIGATE CAFE HARDCORE",
                NamedTextColor.GOLD
            ).decorate(
                TextDecoration.BOLD
            );

        Component subtitleText =
            Component.text(
                "Attempt #"
                    + attemptNumber,
                NamedTextColor.GREEN
            );

        Title title =
            Title.title(
                titleText,
                subtitleText,
                Title.Times.times(
                    Duration.ofMillis(250),
                    Duration.ofSeconds(3),
                    Duration.ofMillis(750)
                )
            );

        Component chatMessage =
            Component.text()
                .append(
                    Component.text(
                        "[Instigate Cafe Hardcore] ",
                        NamedTextColor.GOLD
                    )
                )
                .append(
                    Component.text(
                        "Attempt #"
                            + attemptNumber
                            + " has begun.",
                        NamedTextColor.GREEN
                    )
                )
                .build();

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
             * A failed rotation must not leave playtime running.
             */
            try {
                telemetryManager.endSession(
                    player.getUniqueId()
                );
            } catch (
                IOException exception
            ) {
                plugin.getLogger().severe(
                    "Unable to close telemetry session for "
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
                                    "Unable to teleport "
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
                    "Unable to move "
                        + player.getName()
                        + " to the safety lobby."
                );

                exception.printStackTrace();
            }
        }

        plugin.getServer().broadcast(
            Component.text(
                "[Instigate Cafe Hardcore] "
                    + "World rotation failed. "
                    + "Players have been moved to the safety lobby. "
                    + "A server restart will recover the world pipeline.",
                NamedTextColor.RED
            )
        );
    }

    public synchronized boolean isRotationInProgress() {
        return rotationInProgress;
    }
}