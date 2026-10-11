package dev.instigatehardcore.listener;

import dev.instigatehardcore.world.WorldSet;
import dev.instigatehardcore.world.WorldSetManager;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Objects;

/**
 * Revokes advancements when a player enters a NEW hardcore attempt, not
 * when travelling between the Overworld/Nether/End of the same attempt.
 *
 * The last-reset attempt is stored on the player's persistent data container
 * so reconnecting within an attempt doesn't revoke earned progress again.
 */
public final class AttemptAdvancementResetListener implements Listener {

    private final JavaPlugin plugin;
    private final WorldSetManager worlds;
    private final NamespacedKey resetAttemptKey;

    public AttemptAdvancementResetListener(JavaPlugin plugin, WorldSetManager worlds) {
        this.plugin = Objects.requireNonNull(plugin);
        this.worlds = Objects.requireNonNull(worlds);
        this.resetAttemptKey = new NamespacedKey(plugin, "advancements_reset_attempt");
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        // Defer until the teleport and world assignment have settled.
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> resetIfNeeded(player));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // PlayerJoinListener positions players asynchronously. The retries
        // cover slower chunk loads while the persisted attempt marker makes
        // repeated checks harmless.
        Player player = event.getPlayer();
        for (long delay : new long[]{5L, 25L, 65L}) {
            plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> resetIfNeeded(player),
                delay
            );
        }
    }

    private void resetIfNeeded(Player player) {
        if (!player.isOnline()) {
            return;
        }
        Integer destinationAttempt = attemptOf(player);
        if (destinationAttempt == null) {
            return; // Safety lobby or retired world, not an attempt.
        }

        Integer lastReset = player.getPersistentDataContainer()
            .get(resetAttemptKey, PersistentDataType.INTEGER);
        if (Objects.equals(lastReset, destinationAttempt)) {
            return;
        }

        int advancements = 0;
        int criteria = 0;
        try {
            Iterator<Advancement> iterator = Bukkit.advancementIterator();
            while (iterator.hasNext()) {
                Advancement advancement = iterator.next();
                AdvancementProgress progress = player.getAdvancementProgress(advancement);
                var awarded = new ArrayList<>(progress.getAwardedCriteria());
                if (awarded.isEmpty()) {
                    continue;
                }
                advancements++;
                for (String criterion : awarded) {
                    if (progress.revokeCriteria(criterion)) {
                        criteria++;
                    }
                }
            }
            // Mark only after all revocations succeed. Safe on reconnect.
            player.getPersistentDataContainer().set(
                resetAttemptKey, PersistentDataType.INTEGER, destinationAttempt
            );
            plugin.getLogger().info("Reset advancements for " + player.getName()
                + " in attempt #" + destinationAttempt + " (" + advancements
                + " advancements, " + criteria + " criteria).");
        } catch (RuntimeException exception) {
            // Never interrupt the world rotation / joining pipeline.
            plugin.getLogger().warning("Unable to reset advancements for "
                + player.getName() + " in attempt #" + destinationAttempt
                + ": " + exception.getMessage());
        }
    }

    private Integer attemptOf(Player player) {
        WorldSet active = worlds.getActiveWorldSet();
        if (active != null && active.contains(player.getWorld())) {
            return active.attemptNumber();
        }
        WorldSet standby = worlds.getStandbyWorldSet();
        if (standby != null && standby.contains(player.getWorld())) {
            return standby.attemptNumber();
        }
        return null;
    }
}
