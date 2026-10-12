package dev.instigatehardcore.listener;

import dev.instigatehardcore.core.RunManager;
import dev.instigatehardcore.ui.InstigateTheme;
import dev.instigatehardcore.ui.HeartDisplayFormatter;
import dev.instigatehardcore.world.WorldSetManager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.util.Objects;

/** Global health alerts for players in the active attempt; no replay/reset spam. */
public final class LowHealthAlertMonitor {
    private static final TextColor ORANGE = TextColor.color(0xF3A64D);

    private final JavaPlugin plugin;
    private final RunManager runManager;
    private final WorldSetManager worlds;
    private final TieredHealthAlertPolicy policy;
    private final boolean criticalSoundEnabled;
    private final double warningHearts;
    private final double criticalHearts;
    private BukkitTask task;
    private int nextAlarmTick = Integer.MIN_VALUE;

    public LowHealthAlertMonitor(JavaPlugin plugin, RunManager runManager,
                                 WorldSetManager worlds, double warningHearts,
                                 double warningRearmHearts, double criticalHearts,
                                 double criticalRearmHearts, boolean criticalSoundEnabled) {
        this.plugin = Objects.requireNonNull(plugin);
        this.runManager = Objects.requireNonNull(runManager);
        this.worlds = Objects.requireNonNull(worlds);
        this.policy = new TieredHealthAlertPolicy(
            warningHearts, warningRearmHearts, criticalHearts, criticalRearmHearts);
        this.criticalSoundEnabled = criticalSoundEnabled;
        this.warningHearts = warningHearts;
        this.criticalHearts = criticalHearts;
    }

    public void start() {
        if (task != null) return;
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
            policy.clear();
            return;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!player.isOnline() || player.isDead()
                    || !worlds.isActiveWorld(player.getWorld())) continue;
            double health = player.getHealth();
            TieredHealthAlertPolicy.Level level = policy.evaluate(player.getUniqueId(), health, true);
            if (level == TieredHealthAlertPolicy.Level.NONE) continue;

            String hearts = HeartDisplayFormatter.hearts(health);
            boolean critical = level == TieredHealthAlertPolicy.Level.CRITICAL;
            TextColor color = critical ? InstigateTheme.ERROR : ORANGE;
            String label = BigDecimal.valueOf(critical ? criticalHearts : warningHearts)
                .stripTrailingZeros().toPlainString();
            plugin.getServer().broadcast(InstigateTheme.chat(Component.text()
                .append(Component.text("⚠ ", color))
                .append(Component.text(player.getName(), InstigateTheme.TEXT))
                .append(Component.text(" is below " + label + " hearts (", color))
                .append(Component.text(hearts + " ♥", color))
                .append(Component.text(")!", color))
                .build()
            ));
            if (critical && criticalSoundEnabled) playCriticalAlarm();
        }
    }

    private void playCriticalAlarm() {
        int tick = Bukkit.getCurrentTick();
        if (tick < nextAlarmTick) return; // Multiple simultaneous critical alerts: one alarm.
        nextAlarmTick = tick + 12;
        // Play locally to each online player, independent of the victim's distance/world.
        for (Player listener : plugin.getServer().getOnlinePlayers()) {
            listener.playSound(listener.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL,
                SoundCategory.MASTER, 1.0f, 0.85f);
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            // Do not leak the second alarm note into the reset or the Deathcam.
            if (!runManager.isActive()) return;
            for (Player listener : plugin.getServer().getOnlinePlayers()) {
                listener.playSound(listener.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL,
                    SoundCategory.MASTER, 1.0f, 1.25f);
            }
        }, 6L);
    }
}
