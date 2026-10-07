package dev.instigatehardcore.listener;

import dev.instigatehardcore.core.RunManager;
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

    public DeathListener(
        JavaPlugin plugin,
        RunManager runManager,
        StatsManager statsManager
    ) {
        this.plugin = Objects.requireNonNull(plugin);
        this.runManager = Objects.requireNonNull(runManager);
        this.statsManager = Objects.requireNonNull(statsManager);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();

        /*
         * Only the first death during an ACTIVE run may
         * end the current hardcore attempt.
         */
        if (!runManager.beginEnding()) {
            plugin.getLogger().fine(
                "Ignoring death of "
                    + player.getName()
                    + " because run state is "
                    + runManager.getState()
            );

            return;
        }

        int attemptNumber = statsManager.getCurrentAttempt();

        PlayerStats playerStats = recordDeath(player);

        plugin.getLogger().info(
            "[Instigate Cafe Hardcore] "
                + player.getName()
                + " ended attempt #"
                + attemptNumber
                + "."
        );

        announceRunEnd(
            event,
            attemptNumber,
            playerStats
        );
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
                statsManager.getDeaths(player.getUniqueId())
            );
        }
    }

    private void announceRunEnd(
        PlayerDeathEvent event,
        int attemptNumber,
        PlayerStats playerStats
    ) {
        Component deathMessage = event.deathMessage();

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
            event.getEntity().getName() + " died.",
            NamedTextColor.WHITE
        );

        Component deathCount = Component.text(
            playerStats.name()
                + " now has "
                + playerStats.deaths()
                + " total "
                + (playerStats.deaths() == 1 ? "death." : "deaths."),
            NamedTextColor.GRAY
        );

        Component resetMessage = Component.text(
            "Resetting in 10 seconds...",
            NamedTextColor.GRAY
        );

        plugin.getServer().broadcast(Component.empty());
        plugin.getServer().broadcast(divider);
        plugin.getServer().broadcast(brand);
        plugin.getServer().broadcast(attempt);
        plugin.getServer().broadcast(Component.empty());

        if (deathMessage != null) {
            plugin.getServer().broadcast(deathMessage);
        } else {
            plugin.getServer().broadcast(fallbackDeathMessage);
        }

        plugin.getServer().broadcast(deathCount);
        plugin.getServer().broadcast(Component.empty());
        plugin.getServer().broadcast(resetMessage);
        plugin.getServer().broadcast(divider);
        plugin.getServer().broadcast(Component.empty());
    }
}