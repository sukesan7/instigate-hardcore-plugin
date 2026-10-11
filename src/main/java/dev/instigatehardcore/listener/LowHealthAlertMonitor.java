package dev.instigatehardcore.listener;

import dev.instigatehardcore.core.RunManager;
import dev.instigatehardcore.ui.InstigateTheme;
import dev.instigatehardcore.world.WorldSetManager;

import net.kyori.adventure.text.Component;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;
import java.util.Objects;

/** Global, once-per-danger-episode health warning for ACTIVE participants. */
public final class LowHealthAlertMonitor {
    private final JavaPlugin plugin;
    private final RunManager runManager;
    private final WorldSetManager worlds;
    private final LowHealthAlertPolicy policy;
    private BukkitTask task;

    public LowHealthAlertMonitor(
        JavaPlugin plugin, RunManager runManager, WorldSetManager worlds,
        double thresholdHearts, double rearmHearts
    ) {
        this.plugin = Objects.requireNonNull(plugin);
        this.runManager = Objects.requireNonNull(runManager);
        this.worlds = Objects.requireNonNull(worlds);
        this.policy = new LowHealthAlertPolicy(thresholdHearts, rearmHearts);
    }

    public void start() {
        if (task != null) return;
        // Poll every 2 ticks (0.1s nominal) to catch damage, poison,
        // commands and healing without relying on pre-damage event values.
        task = plugin.getServer().getScheduler().runTaskTimer(
            plugin, this::sample, 2L, 2L
        );
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        policy.clear();
    }

    private void sample() {
        if (!runManager.isActive()) {
            // Also reset across attempts, even if the same user was low.
            policy.clear();
            return;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            boolean eligible = player.isOnline() && !player.isDead()
                && worlds.isActiveWorld(player.getWorld());
            if (!eligible) continue;
            double health = player.getHealth();
            if (!policy.check(player.getUniqueId(), health, true)) continue;

            double hearts = Math.floor(health * 5.0) / 10.0;
            String healthLabel = String.format(Locale.ROOT, "%.1f", hearts);
            plugin.getServer().broadcast(
                InstigateTheme.chat(Component.text()
                    .append(Component.text("⚠ ", InstigateTheme.ERROR))
                    .append(Component.text(player.getName(), InstigateTheme.TEXT))
                    .append(Component.text(" is below 2 hearts (", InstigateTheme.ERROR))
                    .append(Component.text(healthLabel + " ♥", InstigateTheme.PURPLE))
                    .append(Component.text(")!", InstigateTheme.ERROR))
                    .build()
                )
            );
        }
    }
}
