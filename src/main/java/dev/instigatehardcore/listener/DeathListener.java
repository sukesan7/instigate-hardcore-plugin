package dev.instigatehardcore.listener;

import dev.instigatehardcore.core.RunManager;
import dev.instigatehardcore.countdown.CountdownManager;
import dev.instigatehardcore.player.PlayerResetManager;
import dev.instigatehardcore.stats.PlayerStats;
import dev.instigatehardcore.stats.StatsManager;
import dev.instigatehardcore.world.WorldRotationManager;
import dev.instigatehardcore.world.WorldSetManager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.Objects;

public final class DeathListener implements Listener {

    private final JavaPlugin plugin;
    private final RunManager runManager;
    private final StatsManager statsManager;
    private final CountdownManager countdownManager;
    private final PlayerResetManager playerResetManager;
    private final WorldSetManager worldSetManager;
    private final WorldRotationManager worldRotationManager;

    public DeathListener(
        JavaPlugin plugin,
        RunManager runManager,
        StatsManager statsManager,
        CountdownManager countdownManager,
        PlayerResetManager playerResetManager,
        WorldSetManager worldSetManager,
        WorldRotationManager worldRotationManager
    ) {
        this.plugin =
            Objects.requireNonNull(plugin);

        this.runManager =
            Objects.requireNonNull(runManager);

        this.statsManager =
            Objects.requireNonNull(statsManager);

        this.countdownManager =
            Objects.requireNonNull(
                countdownManager
            );

        this.playerResetManager =
            Objects.requireNonNull(
                playerResetManager
            );

        this.worldSetManager =
            Objects.requireNonNull(
                worldSetManager
            );

        this.worldRotationManager =
            Objects.requireNonNull(
                worldRotationManager
            );
    }

    @EventHandler
    public void onPlayerDeath(
        PlayerDeathEvent event
    ) {
        Player player =
            event.getEntity();

        /*
         * Only deaths occurring inside the currently ACTIVE
         * attempt are relevant to the hardcore campaign.
         */
        if (
            !worldSetManager.isActiveWorld(
                player.getWorld()
            )
        ) {
            plugin.getLogger().fine(
                "Ignoring death of "
                    + player.getName()
                    + " outside the active WorldSet."
            );

            return;
        }

        /*
         * Preserve Minecraft's generated death message before
         * suppressing the vanilla broadcast.
         */
        Component deathMessage =
            event.deathMessage();

        /*
         * Only the first death while ACTIVE may end the run.
         */
        if (!runManager.beginEnding()) {
            event.deathMessage(
                null
            );

            event.getDrops().clear();

            event.setDroppedExp(
                0
            );

            plugin.getLogger().fine(
                "Ignoring death of "
                    + player.getName()
                    + " because run state is "
                    + runManager.getState()
            );

            return;
        }

        /*
         * The attempt has now officially ended.
         */
        event.deathMessage(
            null
        );

        /*
         * Nothing from the failed attempt should remain useful.
         */
        event.getDrops().clear();

        event.setDroppedExp(
            0
        );

        int attemptNumber =
            statsManager.getCurrentAttempt();

        PlayerStats playerStats =
            recordDeath(
                player
            );

        plugin.getLogger().info(
            "[Instigate Cafe Hardcore] "
                + player.getName()
                + " ended attempt #"
                + attemptNumber
                + "."
        );

        announceRunEnd(
            player,
            deathMessage,
            attemptNumber,
            playerStats
        );

        /*
         * Clear failed-attempt state and move everyone into
         * spectator mode while the countdown runs.
         */
        playerResetManager
            .beginCountdownPhase();

        startCountdown(
            attemptNumber
        );
    }

    private PlayerStats recordDeath(
        Player player
    ) {
        try {
            return statsManager.recordDeath(
                player.getUniqueId(),
                player.getName()
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

            return new PlayerStats(
                player.getUniqueId(),
                player.getName(),
                statsManager.getDeaths(
                    player.getUniqueId()
                )
            );
        }
    }

    private void startCountdown(
        int attemptNumber
    ) {
        boolean started =
            countdownManager.startCountdown(
                attemptNumber,
                this::onCountdownComplete
            );

        if (!started) {
            plugin.getLogger().warning(
                "[Instigate Cafe Hardcore] "
                    + "Attempted to start a second reset countdown."
            );
        }
    }

    /**
     * Once the countdown reaches zero, transition into
     * RESETTING and promote the prepared standby world.
     */
    private void onCountdownComplete() {
        if (!runManager.beginResetting()) {
            plugin.getLogger().severe(
                "[Instigate Cafe Hardcore] "
                    + "Countdown completed but run state could "
                    + "not transition to RESETTING."
            );

            return;
        }

        plugin.getLogger().info(
            "[Instigate Cafe Hardcore] "
                + "Beginning seamless world rotation."
        );

        boolean rotated =
            worldRotationManager
                .rotateToStandby();

        if (!rotated) {
            plugin.getLogger().severe(
                "[Instigate Cafe Hardcore] "
                    + "Seamless world rotation did not complete."
            );
        }
    }

    private void announceRunEnd(
        Player player,
        Component deathMessage,
        int attemptNumber,
        PlayerStats playerStats
    ) {
        Component divider =
            Component.text(
                "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━",
                NamedTextColor.DARK_RED
            );

        Component brand =
            Component.text(
                "INSTIGATE CAFE HARDCORE",
                NamedTextColor.GOLD
            );

        Component attempt =
            Component.text(
                "Attempt #"
                    + attemptNumber
                    + " has ended",
                NamedTextColor.RED
            );

        Component fallbackDeathMessage =
            Component.text(
                player.getName()
                    + " died.",
                NamedTextColor.WHITE
            );

        Component deathCount =
            Component.text(
                playerStats.name()
                    + " now has "
                    + playerStats.deaths()
                    + " total "
                    + (
                        playerStats.deaths() == 1
                            ? "death."
                            : "deaths."
                    ),
                NamedTextColor.GRAY
            );

        Component nextAttemptMessage =
            Component.text(
                "Next attempt in "
                    + countdownManager
                        .getDurationSeconds()
                    + " seconds...",
                NamedTextColor.GRAY
            );

        plugin.getServer().broadcast(
            Component.empty()
        );

        plugin.getServer().broadcast(
            divider
        );

        plugin.getServer().broadcast(
            brand
        );

        plugin.getServer().broadcast(
            attempt
        );

        plugin.getServer().broadcast(
            Component.empty()
        );

        plugin.getServer().broadcast(
            deathMessage != null
                ? deathMessage
                : fallbackDeathMessage
        );

        plugin.getServer().broadcast(
            deathCount
        );

        plugin.getServer().broadcast(
            Component.empty()
        );

        plugin.getServer().broadcast(
            nextAttemptMessage
        );

        plugin.getServer().broadcast(
            divider
        );

        plugin.getServer().broadcast(
            Component.empty()
        );
    }
}