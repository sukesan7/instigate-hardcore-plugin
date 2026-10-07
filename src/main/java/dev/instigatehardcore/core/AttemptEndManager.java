package dev.instigatehardcore.core;

import dev.instigatehardcore.countdown.CountdownManager;
import dev.instigatehardcore.player.PlayerResetManager;
import dev.instigatehardcore.stats.StatsManager;
import dev.instigatehardcore.telemetry.PlayerDeathRecord;
import dev.instigatehardcore.telemetry.PlayerTelemetryManager;
import dev.instigatehardcore.world.WorldRotationManager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

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
            Objects.requireNonNull(plugin);

        this.runManager =
            Objects.requireNonNull(runManager);

        this.statsManager =
            Objects.requireNonNull(statsManager);

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
     * Ends the active attempt because a player died.
     *
     * Only the first caller that successfully transitions
     * ACTIVE -> ENDING may end the attempt.
     */
    public synchronized boolean endFromPlayerDeath(
        Player player,
        String deathMessage,
        String deathCause
    ) {
        Objects.requireNonNull(player);
        Objects.requireNonNull(deathMessage);
        Objects.requireNonNull(deathCause);

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
     * Ends the active attempt administratively.
     *
     * No player receives a death and no death telemetry record
     * is created.
     */
    public synchronized boolean endFromAdminReset(
        CommandSender sender
    ) {
        Objects.requireNonNull(sender);

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

        return runManager.beginEnding();
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
            telemetryManager.recordDeath(
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
        /*
         * Everybody is removed from gameplay immediately.
         */
        playerResetManager
            .beginCountdownPhase();

        countdownManager
            .startCountdown(
                attempt,
                () -> {
                    if (!runManager.beginResetting()) {
                        plugin.getLogger().severe(
                            "[Instigate Cafe Hardcore] "
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
                            "[Instigate Cafe Hardcore] "
                                + "World rotation failed after attempt #"
                                + attempt
                                + "."
                        );
                    }
                }
            );
    }

    private void announcePlayerDeath(
        Player player,
        int attempt,
        int totalDeaths,
        String deathMessage
    ) {
        Component divider =
            divider();

        plugin.getServer().broadcast(
            divider
        );

        plugin.getServer().broadcast(
            brand()
        );

        plugin.getServer().broadcast(
            Component.text(
                "Attempt #"
                    + attempt
                    + " has ended",
                NamedTextColor.RED
            )
        );

        plugin.getServer().broadcast(
            Component.empty()
        );

        plugin.getServer().broadcast(
            Component.text(
                deathMessage,
                NamedTextColor.WHITE
            )
        );

        plugin.getServer().broadcast(
            Component.text()
                .append(
                    Component.text(
                        player.getName(),
                        NamedTextColor.RED
                    )
                )
                .append(
                    Component.text(
                        " now has "
                            + totalDeaths
                            + " total death"
                            + (
                                totalDeaths == 1
                                    ? ""
                                    : "s"
                            )
                            + ".",
                        NamedTextColor.GRAY
                    )
                )
                .build()
        );

        plugin.getServer().broadcast(
            Component.empty()
        );

        plugin.getServer().broadcast(
            nextAttemptMessage()
        );

        plugin.getServer().broadcast(
            divider
        );
    }

    private void announceAdminReset(
        CommandSender sender,
        int attempt
    ) {
        Component divider =
            divider();

        plugin.getServer().broadcast(
            divider
        );

        plugin.getServer().broadcast(
            brand()
        );

        plugin.getServer().broadcast(
            Component.text(
                "Attempt #"
                    + attempt
                    + " has been reset",
                NamedTextColor.RED
            )
        );

        plugin.getServer().broadcast(
            Component.empty()
        );

        plugin.getServer().broadcast(
            Component.text()
                .append(
                    Component.text(
                        "Administrative reset by ",
                        NamedTextColor.GRAY
                    )
                )
                .append(
                    Component.text(
                        sender.getName(),
                        NamedTextColor.WHITE
                    )
                )
                .append(
                    Component.text(
                        ".",
                        NamedTextColor.GRAY
                    )
                )
                .build()
        );

        plugin.getServer().broadcast(
            Component.text(
                "No player death was recorded.",
                NamedTextColor.DARK_GRAY
            )
        );

        plugin.getServer().broadcast(
            Component.empty()
        );

        plugin.getServer().broadcast(
            nextAttemptMessage()
        );

        plugin.getServer().broadcast(
            divider
        );
    }

    private Component nextAttemptMessage() {
        return Component.text(
            "Next attempt in "
                + countdownManager
                    .getDurationSeconds()
                + " seconds...",
            NamedTextColor.GRAY
        );
    }

    private Component brand() {
        return Component.text(
            "INSTIGATE CAFE HARDCORE",
            NamedTextColor.GOLD
        ).decorate(
            TextDecoration.BOLD
        );
    }

    private Component divider() {
        return Component.text(
            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━",
            NamedTextColor.DARK_GRAY
        );
    }
}