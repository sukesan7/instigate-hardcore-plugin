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
     * May be null when scoreboard.enabled=false.
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

            /*
             * If beginRotation() already persisted ROTATING,
             * startup recovery will finish this transaction on
             * the next server restart.
             *
             * We do not attempt to roll the transaction backward.
             */
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
         * TRANSACTION BOUNDARY
         *
         * Persist our intention to advance BEFORE moving the
         * first player.
         *
         * If Paper dies anywhere after this succeeds:
         *
         * phase=ROTATING
         *
         * tells startup recovery that the failed attempt must not
         * resume and the standby attempt must become ACTIVE.
         */
        worldSetManager
            .beginRotation();

        /*
         * Everyone should already be in spectator mode from the
         * countdown.
         *
         * Perform the final state wipe and move all connected
         * players into the prepared standby world.
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
         * STANDBY -> ACTIVE
         * ACTIVE  -> RETIRED
         *
         * promoteStandby() also persists a new STABLE pipeline.
         */
        WorldSet retiredWorldSet =
            worldSetManager
                .promoteStandby();

        /*
         * Advance persistent campaign statistics.
         *
         * If Paper crashes between promoteStandby() and this call,
         * startup recovery detects activeWorld = stats + 1 and
         * advances StatsManager automatically.
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
         * This starts a fresh runtime timer.
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
         * Dispose of the previous attempt.
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
         * Generate the next standby shortly afterward.
         *
         * Its attempt number and seed have already been persisted
         * by promoteStandby().
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
     * Emergency fallback.
     *
     * Players remain connected but are removed from campaign
     * gameplay until the server can recover safely.
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

                /*
                 * prepareForCountdown() may need to respawn a dead
                 * player first, so defer the lobby teleport by one
                 * tick as well.
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