package dev.instigatehardcore.listener;

import dev.instigatehardcore.core.RunManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class DeathListener implements Listener {

    private final JavaPlugin plugin;
    private final RunManager runManager;

    public DeathListener(JavaPlugin plugin, RunManager runManager) {
        this.plugin = Objects.requireNonNull(plugin);
        this.runManager = Objects.requireNonNull(runManager);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();

        /*
         * Only the first death during an ACTIVE run is allowed
         * to end the current hardcore attempt.
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

        plugin.getLogger().info(
            "[Instigate Cafe Hardcore] "
                + player.getName()
                + " ended the current hardcore attempt."
        );

        /*
         * Temporary attempt number.
         *
         * This will be replaced by StatsManager once persistent
         * campaign data is implemented.
         */
        int attemptNumber = 1;

        announceRunEnd(event, attemptNumber);
    }

    private void announceRunEnd(
        PlayerDeathEvent event,
        int attemptNumber
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
            "Attempt #" + attemptNumber,
            NamedTextColor.RED
        );

        Component fallbackDeathMessage = Component.text(
            event.getEntity().getName() + " died.",
            NamedTextColor.WHITE
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

        plugin.getServer().broadcast(Component.empty());
        plugin.getServer().broadcast(resetMessage);
        plugin.getServer().broadcast(divider);
        plugin.getServer().broadcast(Component.empty());
    }
}