package dev.instigatehardcore.listener;

import dev.instigatehardcore.core.RunManager;
import dev.instigatehardcore.participation.AttemptParticipantManager;
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

    private final AttemptParticipantManager participantManager;

    public PlayerJoinListener(
        JavaPlugin plugin,
        StatsManager statsManager,
        HardcoreScoreboardManager scoreboardManager,
        RunManager runManager,
        PlayerResetManager playerResetManager,
        WorldSetManager worldSetManager,
        AttemptParticipantManager participantManager
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

        this.participantManager =
            Objects.requireNonNull(
                participantManager
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
         * Give Paper time to finish restoring the player's
         * saved world/location/gamemode before enforcing
         * hardcore campaign state.
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
         * Anyone joining while the run is ENDING or RESETTING
         * must stay outside normal gameplay.
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
         * Player belongs to an old attempt, lobby world,
         * or some other stale world.
         *
         * Give them a clean entry into the current attempt.
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

                recordParticipation(
                    player
                );

                scheduleActiveStateVerification(
                    player
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
         * Returning player is already inside the current attempt.
         *
         * Preserve their inventory/location, but ensure Paper has
         * not restored them in spectator mode after recovery.
         */
        normalizeActivePlayer(
            player
        );

        recordParticipation(
            player
        );

        scheduleActiveStateVerification(
            player
        );
    }

    /**
     * Records this player as a participant in the current attempt.
     *
     * Their UUID is only counted once for that attempt.
     */
    private void recordParticipation(
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

        try {
            boolean firstParticipation =
                participantManager
                    .recordParticipant(
                        statsManager
                            .getCurrentAttempt(),
                        player.getUniqueId(),
                        player.getName()
                    );

            if (firstParticipation) {
                plugin.getLogger().info(
                    "[Instigate Cafe Hardcore] "
                        + player.getName()
                        + " joined attempt #"
                        + statsManager.getCurrentAttempt()
                        + " as a participant."
                );
            }
        } catch (
            IOException exception
        ) {
            plugin.getLogger().severe(
                "Failed to record attempt participation for "
                    + player.getName()
                    + "."
            );

            exception.printStackTrace();
        }
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
     * Paper may apply additional login state shortly after the
     * join event, so verify gameplay state again a few ticks later.
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

                        /*
                         * Idempotent. This also helps guarantee
                         * participation is recorded after recovery.
                         */
                        recordParticipation(
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
         * prepareForCountdown() may need to respawn a dead player,
         * so defer the lobby teleport by another tick.
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