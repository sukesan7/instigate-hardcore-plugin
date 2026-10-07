package dev.instigatehardcore.listener;

import dev.instigatehardcore.core.RunManager;
import dev.instigatehardcore.player.PlayerResetManager;
import dev.instigatehardcore.scoreboard.HardcoreScoreboardManager;
import dev.instigatehardcore.stats.StatsManager;
import dev.instigatehardcore.world.WorldSetManager;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.Objects;

public final class PlayerJoinListener implements Listener {

    private static final long JOIN_PLACEMENT_DELAY_TICKS = 1L;
    private static final long JOIN_VERIFICATION_DELAY_TICKS = 2L;

    private final JavaPlugin plugin;
    private final StatsManager statsManager;
    private final HardcoreScoreboardManager scoreboardManager;
    private final RunManager runManager;
    private final PlayerResetManager playerResetManager;
    private final WorldSetManager worldSetManager;

    public PlayerJoinListener(
        JavaPlugin plugin,
        StatsManager statsManager,
        HardcoreScoreboardManager scoreboardManager,
        RunManager runManager,
        PlayerResetManager playerResetManager,
        WorldSetManager worldSetManager
    ) {
        this.plugin =
            Objects.requireNonNull(plugin);

        this.statsManager =
            Objects.requireNonNull(statsManager);

        /*
         * May be null when scoreboard.enabled=false.
         */
        this.scoreboardManager =
            scoreboardManager;

        this.runManager =
            Objects.requireNonNull(runManager);

        this.playerResetManager =
            Objects.requireNonNull(
                playerResetManager
            );

        this.worldSetManager =
            Objects.requireNonNull(
                worldSetManager
            );
    }

    @EventHandler
    public void onPlayerJoin(
        PlayerJoinEvent event
    ) {
        Player player =
            event.getPlayer();

        registerPlayerStats(
            player
        );

        if (scoreboardManager != null) {
            scoreboardManager.assign(
                player
            );

            scoreboardManager.refresh();
        }

        /*
         * Wait until Paper has finished its normal login/world
         * restoration before enforcing campaign state.
         */
        plugin.getServer()
            .getScheduler()
            .runTaskLater(
                plugin,
                () -> placePlayer(
                    player
                ),
                JOIN_PLACEMENT_DELAY_TICKS
            );
    }

    private void registerPlayerStats(
        Player player
    ) {
        try {
            statsManager.ensurePlayer(
                player.getUniqueId(),
                player.getName()
            );
        } catch (
            IOException exception
        ) {
            plugin.getLogger().severe(
                "Failed to persist player data for "
                    + player.getName()
                    + "."
            );

            exception.printStackTrace();
        }
    }

    private void placePlayer(
        Player player
    ) {
        if (!player.isOnline()) {
            return;
        }

        if (runManager.isActive()) {
            placeIntoActiveAttempt(
                player
            );

            return;
        }

        /*
         * A player joining while ENDING or RESETTING may not
         * participate in gameplay.
         */
        sendToSafetyLobby(
            player
        );
    }

    private void placeIntoActiveAttempt(
        Player player
    ) {
        Location activeSpawn =
            worldSetManager
                .getActiveWorldSet()
                .getSpawnLocation();

        /*
         * If Paper restored this player into a stale world from
         * an older attempt, perform the complete attempt reset.
         */
        if (
            !worldSetManager.isActiveWorld(
                player.getWorld()
            )
        ) {
            try {
                playerResetManager
                    .prepareForNewAttempt(
                        player,
                        activeSpawn
                    );
            } catch (
                RuntimeException exception
            ) {
                plugin.getLogger().severe(
                    "Failed to move "
                        + player.getName()
                        + " into the active hardcore attempt."
                );

                exception.printStackTrace();

                sendToSafetyLobby(
                    player
                );

                return;
            }

            scheduleActiveStateVerification(
                player
            );

            return;
        }

        /*
         * The player is already in the current ACTIVE WorldSet.
         *
         * Preserve their inventory and position on an ordinary
         * reconnect, but normalize gameplay state.
         *
         * This is especially important after crash recovery:
         * Paper may restore a player in the newly recovered
         * ACTIVE world while their saved gamemode is SPECTATOR.
         */
        normalizeActivePlayer(
            player
        );

        scheduleActiveStateVerification(
            player
        );
    }

    private void normalizeActivePlayer(
        Player player
    ) {
        if (!player.isOnline()) {
            return;
        }

        if (!runManager.isActive()) {
            return;
        }

        if (
            !worldSetManager.isActiveWorld(
                player.getWorld()
            )
        ) {
            return;
        }

        player.setSpectatorTarget(
            null
        );

        if (
            player.getGameMode()
                != GameMode.SURVIVAL
        ) {
            plugin.getLogger().info(
                "[Instigate Cafe Hardcore] "
                    + "Restoring "
                    + player.getName()
                    + " to SURVIVAL in the active attempt."
            );

            player.setGameMode(
                GameMode.SURVIVAL
            );
        }
    }

    /**
     * Paper/vanilla login restoration can make changes shortly
     * after PlayerJoinEvent.
     *
     * Verify the state again a couple of ticks later so recovery
     * cannot leave somebody stuck in spectator mode.
     */
    private void scheduleActiveStateVerification(
        Player player
    ) {
        plugin.getServer()
            .getScheduler()
            .runTaskLater(
                plugin,
                () -> {
                    if (!player.isOnline()) {
                        return;
                    }

                    if (!runManager.isActive()) {
                        return;
                    }

                    if (
                        worldSetManager.isActiveWorld(
                            player.getWorld()
                        )
                    ) {
                        normalizeActivePlayer(
                            player
                        );
                    }
                },
                JOIN_VERIFICATION_DELAY_TICKS
            );
    }

    private void sendToSafetyLobby(
        Player player
    ) {
        playerResetManager
            .prepareForCountdown(
                player
            );

        /*
         * prepareForCountdown() may first need to respawn a dead
         * player, so defer the actual lobby teleport.
         */
        plugin.getServer()
            .getScheduler()
            .runTask(
                plugin,
                () -> {
                    if (!player.isOnline()) {
                        return;
                    }

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

                    boolean teleported =
                        player.teleport(
                            lobbySpawn
                        );

                    if (!teleported) {
                        plugin.getLogger().severe(
                            "Failed to move "
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
    }
}