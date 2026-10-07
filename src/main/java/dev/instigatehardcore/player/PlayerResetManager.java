package dev.instigatehardcore.player;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;

import java.util.Objects;

public final class PlayerResetManager {

    private final JavaPlugin plugin;

    public PlayerResetManager(
        JavaPlugin plugin
    ) {
        this.plugin =
            Objects.requireNonNull(plugin);
    }

    /**
     * Begins the run-ending phase for every online player.
     *
     * At this point the attempt is already over, so no gameplay
     * state needs to survive.
     */
    public void beginCountdownPhase() {
        for (
            Player player :
            plugin.getServer().getOnlinePlayers()
        ) {
            prepareForCountdown(player);
        }
    }

    /**
     * Places a player into the run-ending spectator phase and
     * destroys state belonging to the failed attempt.
     */
    public void prepareForCountdown(
        Player player
    ) {
        Objects.requireNonNull(player);

        clearAttemptInventory(player);
        clearExperience(player);
        clearEffects(player);
        clearTransientState(player);

        /*
         * A player inside PlayerDeathEvent is still considered dead.
         *
         * Respawn them on the next server tick before placing them
         * into spectator mode.
         */
        if (player.isDead()) {
            plugin.getServer()
                .getScheduler()
                .runTask(
                    plugin,
                    () -> respawnIntoSpectator(player)
                );

            return;
        }

        enterSpectator(player);
    }

    private void respawnIntoSpectator(
        Player player
    ) {
        if (!player.isOnline()) {
            return;
        }

        if (player.isDead()) {
            player.spigot().respawn();
        }

        /*
         * Respawning resets some player state internally.
         * Wait one additional tick before applying spectator state.
         */
        plugin.getServer()
            .getScheduler()
            .runTask(
                plugin,
                () -> {
                    if (player.isOnline()) {
                        enterSpectator(player);
                    }
                }
            );
    }

    private void enterSpectator(
        Player player
    ) {
        player.closeInventory();

        player.setGameMode(
            GameMode.SPECTATOR
        );

        player.setSpectatorTarget(
            null
        );

        player.setFireTicks(0);
        player.setFallDistance(0.0f);

        player.setVelocity(
            new Vector(0.0, 0.0, 0.0)
        );
    }

    /**
     * Performs the final clean reset immediately before a player
     * enters the next attempt.
     *
     * Phase 6C will call this with the new world's spawn location.
     */
    public void prepareForNewAttempt(
        Player player,
        Location spawn
    ) {
        Objects.requireNonNull(player);
        Objects.requireNonNull(spawn);

        clearAttemptInventory(player);
        clearExperience(player);
        clearEffects(player);
        clearTransientState(player);
        resetVitals(player);

        player.setSpectatorTarget(null);

        boolean teleported =
            player.teleport(spawn);

        if (!teleported) {
            throw new IllegalStateException(
                "Failed to teleport "
                    + player.getName()
                    + " to the new attempt spawn."
            );
        }

        player.setRespawnLocation(
            spawn,
            true
        );

        player.setGameMode(
            GameMode.SURVIVAL
        );
    }

    /**
     * Nothing stored in the normal player inventory should survive
     * between hardcore attempts.
     */
    private void clearAttemptInventory(
        Player player
    ) {
        player.closeInventory();

        PlayerInventory inventory =
            player.getInventory();

        inventory.clear();

        /*
         * Explicitly clear equipment as well. This is somewhat
         * redundant with inventory.clear(), but intentionally
         * defensive.
         */
        inventory.setHelmet(null);
        inventory.setChestplate(null);
        inventory.setLeggings(null);
        inventory.setBoots(null);

        inventory.setItemInOffHand(null);

        /*
         * Ender chests belong to the attempt as well.
         */
        player.getEnderChest().clear();

        /*
         * Prevent an item held by the inventory cursor from
         * carrying into the next attempt.
         */
        player.setItemOnCursor(null);

        player.updateInventory();
    }

    private void clearExperience(
        Player player
    ) {
        player.setExp(0.0f);
        player.setLevel(0);
        player.setTotalExperience(0);
    }

    private void clearEffects(
        Player player
    ) {
        for (
            PotionEffect effect :
            player.getActivePotionEffects()
        ) {
            player.removePotionEffect(
                effect.getType()
            );
        }
    }

    private void clearTransientState(
        Player player
    ) {
        player.setFireTicks(0);
        player.setFallDistance(0.0f);

        player.setFreezeTicks(0);

        player.setVelocity(
            new Vector(0.0, 0.0, 0.0)
        );

        player.setSprinting(false);
        player.setSneaking(false);
    }

    private void resetVitals(
        Player player
    ) {
        AttributeInstance maxHealth =
            player.getAttribute(
                Attribute.MAX_HEALTH
            );

        if (maxHealth == null) {
            throw new IllegalStateException(
                "MAX_HEALTH attribute unavailable for "
                    + player.getName()
            );
        }

        player.setHealth(
            maxHealth.getValue()
        );

        player.setAbsorptionAmount(
            0.0
        );

        player.setFoodLevel(
            20
        );

        player.setSaturation(
            5.0f
        );

        player.setExhaustion(
            0.0f
        );

        player.setFireTicks(
            0
        );

        player.setFallDistance(
            0.0f
        );
    }
}