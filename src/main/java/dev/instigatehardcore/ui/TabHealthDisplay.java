package dev.instigatehardcore.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Adds a numeric heart count to Tab player names without replacing ping indicators. */
public final class TabHealthDisplay {
    private static final TextColor WARNING = TextColor.color(0xF3A64D);
    private final JavaPlugin plugin;
    private final int updateTicks;
    private final Map<UUID, AppliedName> previous = new HashMap<>();
    private BukkitTask task;

    private record AppliedName(Component base, Component applied) {}

    public TabHealthDisplay(JavaPlugin plugin, int updateTicks) {
        this.plugin = Objects.requireNonNull(plugin);
        if (updateTicks < 1) throw new IllegalArgumentException("updateTicks must be positive");
        this.updateTicks = updateTicks;
    }

    public void start() {
        if (task != null) return;
        refresh();
        task = plugin.getServer().getScheduler().runTaskTimer(
            plugin, this::refresh, updateTicks, updateTicks);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            AppliedName entry = previous.get(player.getUniqueId());
            if (entry != null && player.playerListName().equals(entry.applied())) {
                player.playerListName(entry.base());
            }
        }
        previous.clear();
    }

    private void refresh() {
        Set<UUID> online = new HashSet<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            online.add(id);
            Component current = player.playerListName();
            AppliedName stored = previous.get(id);
            // If another plugin changed the name, preserve its new baseline.
            Component base = stored == null || !current.equals(stored.applied())
                ? current : stored.base();

            double health = player.isDead() ? 0.0 : player.getHealth();
            double hearts = health / 2.0;
            TextColor color = hearts < 2.0 ? InstigateTheme.ERROR
                : hearts < 5.0 ? WARNING : InstigateTheme.AZURE;
            Component updated = base.append(Component.text("  ", InstigateTheme.MUTED))
                .append(Component.text(HeartDisplayFormatter.hearts(health) + " ♥", color));
            if (!updated.equals(current)) player.playerListName(updated);
            previous.put(id, new AppliedName(base, updated));
        }
        previous.keySet().retainAll(online);
    }
}
