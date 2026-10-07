package dev.instigatehardcore.world;

import dev.instigatehardcore.core.RunManager;
import dev.instigatehardcore.player.PlayerResetManager;
import dev.instigatehardcore.scoreboard.HardcoreScoreboardManager;
import dev.instigatehardcore.stats.StatsManager;

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

    /*
     * May be null when the sidebar is disabled.
     */
    private final HardcoreScoreboardManager scoreboardManager;

    private boolean rotationInProgress;

    public WorldRotationManager(
        JavaPlugin plugin,
        RunManager runManager,
        StatsManager statsManager,
        PlayerResetManager playerResetManager,
        WorldSetManager worldSetManager,
        WorldCleanupManager worldCleanupManager,
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

        this.scoreboardManager =
            scoreboardManager;
    }

    /**
     * Seamlessly promotes the prepared standby attempt and
     * transfers every connected player into it.
     */
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
         * Everyone is already in spectator mode from the
         * countdown phase.
         *
         * Perform one final state wipe and teleport them into
         * the prepared standby world.
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
        }

        /*
         * Nobody should remain inside the old attempt now.
         *
         * Promote STANDBY -> ACTIVE and ACTIVE -> RETIRED.
         */
        WorldSet retiredWorldSet =
            worldSetManager
                .promoteStandby();

        /*
         * Advance the persistent campaign attempt number.
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
         *
         * RunManager resets startedAt here, which restarts the
         * scoreboard's elapsed timer from zero.
         */
        if (!runManager.beginNextRun()) {
            throw new IllegalStateException(
                "Unable to transition new attempt to ACTIVE."
            );
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
                + " is now ACTIVE."
        );

        /*
         * The previous attempt is now completely disposable.
         *
         * WorldCleanupManager handles:
         *
         * - waiting for a safe unload point
         * - verifying there are no players inside
         * - unloading End / Nether / Overworld
         * - deleting the world folders asynchronously
         */
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

        /*
         * Shortly afterward, create the standby world for the
         * following attempt.
         */
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

    /**
     * Emergency fallback if world rotation cannot be completed.
     *
     * Players stay connected but are removed from gameplay.
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
                    + "Players have been moved to the safety lobby.",
                NamedTextColor.RED
            )
        );
    }

    public synchronized boolean isRotationInProgress() {
        return rotationInProgress;
    }
}