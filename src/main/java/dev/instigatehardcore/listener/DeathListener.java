package dev.instigatehardcore.listener;

import dev.instigatehardcore.core.RunManager;
import dev.instigatehardcore.countdown.CountdownManager;
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

    public DeathListener(
        JavaPlugin plugin,
        RunManager runManager,
        StatsManager statsManager,
        CountdownManager countdownManager
    ) {
        this.plugin = Objects.requireNonNull(plugin);
        this.runManager = Objects.requireNonNull(runManager);
        this.statsManager = Objects.requireNonNull(statsManager);
        this.countdownManager = Objects.requireNonNull(countdownManager);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();

        /*
         * Preserve Minecraft's generated death message before
         * suppressing the normal server-wide broadcast.
         */
        Component deathMessage = event.deathMessage();

        /*
         * Only the first death during an ACTIVE run is allowed
         * to end the current hardcore attempt.
         */
        if (!runManager.beginEnding()) {
            /*
             * The run is already ending/resetting.
             *
             * Suppress additional vanilla death announcements
             * so the run-ending UI remains clean.
             */
            event.deathMessage(null);

            plugin.getLogger().fine(
                "Ignoring death of "
                    + player.getName()
                    + " because run state is "
                    + runManager.getState()
            );

            return;
        }

        /*
         * We are replacing the normal Minecraft death broadcast
         * with our branded Instigate Cafe Hardcore announcement.
         */
        event.deathMessage(null);

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

        startCountdown(attemptNumber);
    }

    private PlayerStats recordDeath(Player player) {
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

    private void onCountdownComplete() {
        if (!runManager.beginResetting()) {
            plugin.getLogger().severe(
                "[Instigate Cafe Hardcore] "
                    + "Countdown completed but run state could not "
                    + "transition to RESETTING."
            );

            return;
        }

        plugin.getLogger().info(
            "[Instigate Cafe Hardcore] "
                + "Run state transitioned to RESETTING."
        );

        /*
         * Phase 6 will invoke ResetManager here.
         *
         * For now we intentionally stop at RESETTING so that
         * no filesystem or server shutdown operations happen yet.
         */
    }

    private void announceRunEnd(
        Player player,
        Component deathMessage,
        int attemptNumber,
        PlayerStats playerStats
    ) {
        Component divider = Component.text(
            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━",
            NamedTextColor.DARK_RED
        );

        Component brand = Component.text(
            "INSTIGATE CAFE HARDCORE",
            NamedTextColor.GOLD
        );

        Component attempt = Component.text(
            "Attempt #" + attemptNumber + " has ended",
            NamedTextColor.RED
        );

        Component fallbackDeathMessage = Component.text(
            player.getName() + " died.",
            NamedTextColor.WHITE
        );

        Component deathCount = Component.text(
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

        Component resetMessage = Component.text(
            "Resetting in "
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