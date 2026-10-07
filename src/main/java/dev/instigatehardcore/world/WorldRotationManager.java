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

    private static final long STANDBY_GENERATION_DELAY_TICKS = 40L;

    private final JavaPlugin plugin;
    private final RunManager runManager;
    private final StatsManager statsManager;
    private final PlayerResetManager playerResetManager;
    private final WorldSetManager worldSetManager;
    private final HardcoreScoreboardManager scoreboardManager;

    private boolean rotationInProgress;

    public WorldRotationManager(
        JavaPlugin plugin,
        RunManager runManager,
        StatsManager statsManager,
        PlayerResetManager playerResetManager,
        WorldSetManager worldSetManager,
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

        /*
         * Scoreboard may legitimately be disabled in config.yml.
         */
        this.scoreboardManager =
            scoreboardManager;
    }

    /**
     * Seamlessly moves all players from the failed attempt
     * into the pre-generated standby attempt.
     */
    public synchronized boolean rotateToStandby() {
        if (rotationInProgress) {
            plugin.getLogger().warning(
                "[Instigate Cafe Hardcore] "
                    + "World rotation already in progress."
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
            statsManager.getCurrentAttempt()
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

        rotationInProgress = true;

        try {
            performRotation(
                standby
            );

            return true;
        } catch (Exception exception) {
            plugin.getLogger().severe(
                "[Instigate Cafe Hardcore] "
                    + "Seamless world rotation failed."
            );

            exception.printStackTrace();

            moveEveryoneToSafety();

            return false;
        } finally {
            rotationInProgress = false;
        }
    }

    private void performRotation(
        WorldSet standby
    ) throws IOException {

        int oldAttempt =
            statsManager.getCurrentAttempt();

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
         * First move every connected player into the already-created
         * standby world.
         *
         * Their inventory, Ender Chest, XP, potion effects, health,
         * hunger and other per-attempt state are reset here.
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
         * Players are now safely inside the new world.
         *
         * Promote it to ACTIVE in the world pipeline.
         */
        worldSetManager
            .promoteStandby();

        /*
         * Advance persistent campaign statistics.
         */
        int advancedAttempt =
            statsManager.advanceAttempt();

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
         * RESETTING -> ACTIVE
         *
         * This also starts a fresh run timer.
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
         * Generate the next standby shortly after players arrive.
         *
         * This keeps the critical transition itself as short as
         * possible.
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
                                    + "Warning: the next standby world "
                                    + "could not be prepared.",
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
     * Players remain connected but are removed from gameplay if a
     * world rotation cannot be completed safely.
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

                player.teleport(
                    lobbySpawn
                );

                player.setGameMode(
                    GameMode.SPECTATOR
                );
            } catch (
                Exception exception
            ) {
                plugin.getLogger().severe(
                    "Unable to move "
                        + player.getName()
                        + " to the safety world."
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