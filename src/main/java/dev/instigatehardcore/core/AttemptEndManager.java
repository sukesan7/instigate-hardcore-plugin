package dev.instigatehardcore.core;

import dev.instigatehardcore.countdown.CountdownManager;
import dev.instigatehardcore.player.PlayerResetManager;
import dev.instigatehardcore.stats.StatsManager;
import dev.instigatehardcore.telemetry.PlayerDeathRecord;
import dev.instigatehardcore.telemetry.PlayerTelemetryManager;
import dev.instigatehardcore.ui.InstigateTheme;
import dev.instigatehardcore.world.WorldRotationManager;

import net.kyori.adventure.text.Component;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.Objects;

public final class AttemptEndManager {

    private final JavaPlugin plugin;

    private final RunManager runManager;
    private final StatsManager statsManager;
    private final PlayerTelemetryManager telemetryManager;

    private final CountdownManager countdownManager;
    private final PlayerResetManager playerResetManager;
    private final WorldRotationManager worldRotationManager;

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

        if (!beginEnding()) {
            return false;
        }

        int attempt =
            statsManager
                .getCurrentAttempt();

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

        beginCountdown(
            attempt
        );

        return true;
    }

    /**
     * Ends the current ACTIVE attempt administratively.
     *
     * Administrative resets do not record a player death.
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

        beginCountdown(
            attempt
        );

        return true;
    }

    private boolean beginEnding() {
        if (!runManager.isActive()) {
            return false;
        }

        return runManager
            .beginEnding();
    }

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
            statsManager.getDeaths(
                player.getUniqueId()
            );

        try {
            statsManager.recordDeath(
                player.getUniqueId(),
                player.getName()
            );

            totalDeaths =
                statsManager.getDeaths(
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

    private void beginCountdown(
        int attempt
    ) {
        playerResetManager
            .beginCountdownPhase();

        countdownManager
            .startCountdown(
                attempt,
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
        plugin.getServer().broadcast(
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

        plugin.getServer().broadcast(
            InstigateTheme.chat(
                Component.text(
                    deathMessage,
                    InstigateTheme.TEXT
                )
            )
        );

        plugin.getServer().broadcast(
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

        plugin.getServer().broadcast(
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
        plugin.getServer().broadcast(
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

        plugin.getServer().broadcast(
            InstigateTheme.chat(
                InstigateTheme.muted(
                    "No player death was recorded."
                )
            )
        );

        plugin.getServer().broadcast(
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