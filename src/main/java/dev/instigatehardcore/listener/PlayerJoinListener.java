package dev.instigatehardcore.listener;

import dev.instigatehardcore.scoreboard.HardcoreScoreboardManager;
import dev.instigatehardcore.stats.StatsManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.Objects;

public final class PlayerJoinListener
    implements Listener {

    private final JavaPlugin plugin;
    private final StatsManager statsManager;
    private final HardcoreScoreboardManager
        scoreboardManager;

    public PlayerJoinListener(
        JavaPlugin plugin,
        StatsManager statsManager,
        HardcoreScoreboardManager scoreboardManager
    ) {
        this.plugin =
            Objects.requireNonNull(plugin);

        this.statsManager =
            Objects.requireNonNull(statsManager);

        this.scoreboardManager =
            Objects.requireNonNull(
                scoreboardManager
            );
    }

    @EventHandler
    public void onPlayerJoin(
        PlayerJoinEvent event
    ) {
        Player player =
            event.getPlayer();

        try {
            statsManager.ensurePlayer(
                player.getUniqueId(),
                player.getName()
            );
        } catch (IOException exception) {
            plugin.getLogger().severe(
                "Failed to persist player data for "
                    + player.getName()
                    + "."
            );

            exception.printStackTrace();
        }

        scoreboardManager.assign(
            player
        );

        scoreboardManager.refresh();
    }
}