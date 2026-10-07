package dev.instigatehardcore.listener;

import dev.instigatehardcore.core.RunManager;
import dev.instigatehardcore.participation.AttemptParticipantManager;
import dev.instigatehardcore.player.PlayerResetManager;
import dev.instigatehardcore.scoreboard.HardcoreScoreboardManager;
import dev.instigatehardcore.stats.StatsManager;
import dev.instigatehardcore.telemetry.PlayerTelemetryManager;
import dev.instigatehardcore.ui.InstigateTheme;
import dev.instigatehardcore.world.WorldSetManager;

import net.kyori.adventure.text.Component;

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

    private static final long JOIN_PLACEMENT_DELAY_TICKS =
        1L;

    private static final long JOIN_VERIFICATION_DELAY_TICKS =
        2L;

    private final JavaPlugin plugin;

    private final StatsManager statsManager;

    private final HardcoreScoreboardManager scoreboardManager;

    private final RunManager runManager;

    private final PlayerResetManager playerResetManager;
    private final WorldSetManager worldSetManager;

    private final AttemptParticipantManager participantManager;
    private final PlayerTelemetryManager telemetryManager;

    private final boolean allowLateJoiners;

    public PlayerJoinListener(
        JavaPlugin plugin,
        StatsManager statsManager,
        HardcoreScoreboardManager scoreboardManager,
        RunManager runManager,
        PlayerResetManager playerResetManager,
        WorldSetManager worldSetManager,
        AttemptParticipantManager participantManager,
        PlayerTelemetryManager telemetryManager,
        boolean allowLateJoiners
    ) {
        this.plugin =
            Objects.requireNonNull(
                plugin
            );

        this.statsManager =
            Objects.requireNonNull(
                statsManager
            );

        /*
         * May be null when scoreboard.enabled=false.
         */
        this.scoreboardManager =
            scoreboardManager;

        this.runManager =
            Objects.requireNonNull(
                runManager
            );

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

        this.telemetryManager =
            Objects.requireNonNull(
                telemetryManager
            );

        this.allowLateJoiners =
            allowLateJoiners;
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
         * Wait for Paper to finish restoring the player's saved
         * location and gamemode before enforcing campaign state.
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
         * Players joining during ENDING or RESETTING must remain
         * outside active gameplay.
         */
        sendToSafetyLobby(
            player
        );
    }

    private void placeIntoActiveAttempt(
        Player player
    ) {
        if (
            !mayEnterCurrentAttempt(
                player
            )
        ) {
            notifyLateJoinBlocked(
                player
            );

            sendToSafetyLobby(
                player
            );

            return;
        }

        Location activeSpawn =
            worldSetManager
                .getActiveWorldSet()
                .getSpawnLocation();

        /*
         * The player was restored into the lobby, an old attempt,
         * or another stale world.
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

                beginTelemetrySession(
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
         * The player is already inside the current ACTIVE attempt.
         *
         * Preserve inventory/location while correcting any stale
         * spectator state left by crash recovery.
         */
        normalizeActivePlayer(
            player
        );

        recordParticipation(
            player
        );

        beginTelemetrySession(
            player
        );

        scheduleActiveStateVerification(
            player
        );
    }

    /*
     * ------------------------------------------------------------
     * ATTEMPT ADMISSION
     * ------------------------------------------------------------
     */

    private boolean mayEnterCurrentAttempt(
        Player player
    ) {
        int attempt =
            statsManager
                .getCurrentAttempt();

        /*
         * Rejoining an attempt you already participated in is
         * never considered a late join.
         *
         * A participant may disconnect and reconnect freely even
         * when late joining is disabled.
         */
        if (
            participantManager
                .hasParticipant(
                    attempt,
                    player.getUniqueId()
                )
        ) {
            return true;
        }

        /*
         * Normal Instigate Cafe behavior:
         *
         * new players may enter an already-running attempt.
         */
        if (allowLateJoiners) {
            return true;
        }

        /*
         * Bootstrap protection for a completely empty attempt.
         *
         * Without this exception, a fresh server with late joining
         * disabled could start with zero participants and therefore
         * reject every player forever.
         *
         * The first player establishes participation. Once the
         * attempt has a participant, additional new players must
         * wait for the next attempt.
         */
        return participantManager
            .getParticipantCount(
                attempt
            )
            == 0;
    }

    private void notifyLateJoinBlocked(
        Player player
    ) {
        int attempt =
            statsManager
                .getCurrentAttempt();

        player.sendMessage(
            InstigateTheme.chat(
                Component.text()
                    .append(
                        InstigateTheme.attempt(
                            attempt
                        )
                    )
                    .append(
                        InstigateTheme.secondary(
                            " is already in progress. "
                        )
                    )
                    .append(
                        InstigateTheme.text(
                            "You'll join the next attempt."
                        )
                    )
                    .build()
            )
        );

        plugin.getLogger().info(
            "[Instigate Cafe] "
                + player.getName()
                + " was held out of attempt #"
                + attempt
                + " because late joining is disabled."
        );
    }

    /*
     * ------------------------------------------------------------
     * PARTICIPATION
     * ------------------------------------------------------------
     */

    private void recordParticipation(
        Player player
    ) {
        if (
            !isActiveParticipantLocation(
                player
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
                    "[Instigate Cafe] "
                        + player.getName()
                        + " joined attempt #"
                        + statsManager
                            .getCurrentAttempt()
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

    /**
     * Starts actual ACTIVE-attempt playtime accounting.
     *
     * PlayerTelemetryManager.beginSession() is idempotent for an
     * already-running session in the same attempt.
     */
    private void beginTelemetrySession(
        Player player
    ) {
        if (
            !isActiveParticipantLocation(
                player
            )
        ) {
            return;
        }

        telemetryManager.beginSession(
            statsManager
                .getCurrentAttempt(),
            player.getUniqueId(),
            player.getName()
        );
    }

    private boolean isActiveParticipantLocation(
        Player player
    ) {
        return player.isOnline()
            && runManager.isActive()
            && worldSetManager.isActiveWorld(
                player.getWorld()
            );
    }

    private void normalizeActivePlayer(
        Player player
    ) {
        if (
            !isActiveParticipantLocation(
                player
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
                "[Instigate Cafe] "
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
     * Paper can apply additional player state shortly after the
     * join event. Verify once more after login restoration.
     *
     * Phase 8B will strengthen this verification path further.
     */
    private void scheduleActiveStateVerification(
        Player player
    ) {
        plugin.getServer()
            .getScheduler()
            .runTaskLater(
                plugin,
                () -> {
                    if (
                        !isActiveParticipantLocation(
                            player
                        )
                    ) {
                        return;
                    }

                    normalizeActivePlayer(
                        player
                    );

                    /*
                     * Both operations are idempotent.
                     */
                    recordParticipation(
                        player
                    );

                    beginTelemetrySession(
                        player
                    );
                },
                JOIN_VERIFICATION_DELAY_TICKS
            );
    }

    /*
     * ------------------------------------------------------------
     * SAFETY LOBBY
     * ------------------------------------------------------------
     */

    private void sendToSafetyLobby(
        Player player
    ) {
        /*
         * A player being moved out of gameplay should not retain
         * an ACTIVE telemetry session.
         */
        try {
            telemetryManager.endSession(
                player.getUniqueId()
            );
        } catch (
            IOException exception
        ) {
            plugin.getLogger().severe(
                "Failed to close telemetry session for "
                    + player.getName()
                    + "."
            );

            exception.printStackTrace();
        }

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