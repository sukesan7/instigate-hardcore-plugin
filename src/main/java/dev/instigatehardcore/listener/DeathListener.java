package dev.instigatehardcore.listener;

import dev.instigatehardcore.core.RunManager;
import dev.instigatehardcore.countdown.CountdownManager;
import dev.instigatehardcore.player.PlayerResetManager;
import dev.instigatehardcore.stats.PlayerStats;
import dev.instigatehardcore.stats.StatsManager;

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

    public DeathListener(
        JavaPlugin plugin,
        RunManager runManager,
        StatsManager statsManager,
        CountdownManager countdownManager,
        PlayerResetManager playerResetManager
    ) {
        this.plugin = Objects.requireNonNull(plugin);
        this.runManager = Objects.requireNonNull(runManager);
        this.statsManager = Objects.requireNonNull(statsManager);
        this.countdownManager = Objects.requireNonNull(countdownManager);
        this.playerResetManager = Objects.requireNonNull(playerResetManager);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();

        /*
         * Preserve Minecraft's generated death message before
         * suppressing the normal broadcast.
         */
        Component deathMessage = event.deathMessage();

        /*
         * Only the first death during an ACTIVE run may end
         * the current hardcore attempt.
         */
        if (!runManager.beginEnding()) {
            /*
             * The run is already ending/resetting.
             *
             * Suppress additional death spam during the transition.
             */
            event.deathMessage(null);
            event.getDrops().clear();
            event.setDroppedExp(0);

            plugin.getLogger().fine(
                "Ignoring death of "
                    + player.getName()
                    + " because run state is "
                    + runManager.getState()
            );

            return;
        }

        /*
         * The attempt has ended.
         *
         * Replace Minecraft's normal death handling presentation
         * with the Instigate Cafe Hardcore flow.
         */
        event.deathMessage(null);

        /*
         * Nothing from the failed attempt should remain useful.
         */
        event.getDrops().clear();
        event.setDroppedExp(0);

        int attemptNumber =
            statsManager.getCurrentAttempt();

        PlayerStats playerStats =
            recordDeath(player);

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
         * Immediately transition all players out of normal gameplay.
         *
         * Inventories, Ender Chests, XP, potion effects and other
         * per-attempt state are cleared. Survivors become spectators.
         *
         * The player who actually died will be respawned on the next
         * safe tick and then placed into spectator mode too.
         */
        playerResetManager.beginCountdownPhase();

        startCountdown(attemptNumber);
    }

    /**
     * Records the run-ending death in persistent campaign statistics.
     */
    private PlayerStats recordDeath(
        Player player
    ) {
        try {
            return statsManager.recordDeath(
                player.getUniqueId(),
                player.getName()
            );
        } catch (IOException exception) {
            plugin.getLogger().severe(
                "Failed to persist death statistics for "
                    + player.getName()
                    + "."
            );

            exception.printStackTrace();

            /*
             * Continue the run-ending flow even if persistence fails.
             */
            return new PlayerStats(
                player.getUniqueId(),
                player.getName(),
                statsManager.getDeaths(
                    player.getUniqueId()
                )
            );
        }
    }

    /**
     * Starts the configured reset countdown.
     */
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
     * Runs once the countdown reaches zero.
     *
     * Phase 6C will eventually trigger seamless world rotation here.
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
                + "Countdown completed. "
                + "Waiting for seamless world rotation."
        );

        /*
         * Temporary Phase 6A behavior:
         *
         * Everyone remains connected and in spectator mode.
         *
         * Phase 6C will replace this block with something like:
         *
         *     worldRotationManager.rotateToStandbyWorld();
         *
         * No server shutdown or disconnect occurs.
         */
    }

    /**
     * Broadcasts the branded run-ending message.
     */
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
                player.getName() + " died.",
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

        Component resetMessage =
            Component.text(
                "Next attempt in "
                    + countdownManager.getDurationSeconds()
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
            resetMessage
        );

        plugin.getServer().broadcast(
            divider
        );

        plugin.getServer().broadcast(
            Component.empty()
        );
    }
}