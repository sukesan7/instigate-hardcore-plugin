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
         * Give Paper one tick to finish its normal login/world
         * restoration process before enforcing our campaign world.
         */
        plugin.getServer()
            .getScheduler()
            .runTask(
                plugin,
                () -> placePlayer(
                    player
                )
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

        /*
         * During a healthy ACTIVE attempt, players belong in the
         * current active WorldSet.
         */
        if (runManager.isActive()) {

            /*
             * Returning players already inside the active attempt
             * retain their location and current survival progress.
             */
            if (
                worldSetManager.isActiveWorld(
                    player.getWorld()
                )
            ) {
                return;
            }

            Location spawn =
                worldSetManager
                    .getActiveWorldSet()
                    .getSpawnLocation();

            try {
                /*
                 * A player arriving from an old attempt, lobby,
                 * or stale logout world enters the current attempt
                 * as a clean player.
                 */
                playerResetManager
                    .prepareForNewAttempt(
                        player,
                        spawn
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
            }

            return;
        }

        /*
         * Players joining while the current attempt is ENDING or
         * RESETTING must not enter normal gameplay.
         */
        sendToSafetyLobby(
            player
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
         * Wait another tick because prepareForCountdown() may
         * need to respawn a dead player first.
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