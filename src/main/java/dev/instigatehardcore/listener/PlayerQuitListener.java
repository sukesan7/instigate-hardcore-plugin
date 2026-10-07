package dev.instigatehardcore.listener;

import dev.instigatehardcore.telemetry.PlayerTelemetryManager;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.Objects;

public final class PlayerQuitListener implements Listener {

    private final JavaPlugin plugin;
    private final PlayerTelemetryManager telemetryManager;

    public PlayerQuitListener(
        JavaPlugin plugin,
        PlayerTelemetryManager telemetryManager
    ) {
        this.plugin =
            Objects.requireNonNull(
                plugin
            );

        this.telemetryManager =
            Objects.requireNonNull(
                telemetryManager
            );
    }

    @EventHandler(
        priority = EventPriority.MONITOR
    )
    public void onPlayerQuit(
        PlayerQuitEvent event
    ) {
        Player player =
            event.getPlayer();

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
                    + " during disconnect."
            );

            exception.printStackTrace();
        }
    }
}