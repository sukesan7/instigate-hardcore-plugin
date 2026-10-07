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
            Objects.requireNonNull(
                plugin
            );
    }

    /*
     * ------------------------------------------------------------
     * COUNTDOWN PHASE
     * ------------------------------------------------------------
     */

    /**
     * Begins the run-ending phase for every online player.
     *
     * Used when there is no specific location that players should
     * gather around, such as an administrative reset.
     */
    public void beginCountdownPhase() {
        for (
            Player player :
            plugin.getServer()
                .getOnlinePlayers()
        ) {
            prepareForCountdown(
                player
            );
        }
    }

    /**
     * Begins the run-ending phase for every online player and
     * gathers everyone at a specific location.
     *
     * This is used for a real player death so the entire group can
     * spectate the exact place where the attempt ended.
     *
     * The Location retains its World reference, so this naturally
     * supports deaths in:
     *
     * - Overworld
     * - Nether
     * - End
     */
    public void beginCountdownPhase(
        Location focusLocation
    ) {
        Objects.requireNonNull(
            focusLocation
        );

        if (focusLocation.getWorld() == null) {
            throw new IllegalArgumentException(
                "Countdown focus location must belong to a world."
            );
        }

        /*
         * Work from our own immutable snapshot of the location.
         *
         * Bukkit Location itself is mutable.
         */
        Location target =
            focusLocation.clone();

        for (
            Player player :
            plugin.getServer()
                .getOnlinePlayers()
        ) {
            prepareForCountdown(
                player,
                target
            );
        }
    }

    /*
     * ------------------------------------------------------------
     * INDIVIDUAL COUNTDOWN PREPARATION
     * ------------------------------------------------------------
     */

    /**
     * Places a player into the run-ending spectator phase without
     * moving them to a specific location.
     *
     * Used by administrative resets and safety-lobby flows.
     */
    public void prepareForCountdown(
        Player player
    ) {
        Objects.requireNonNull(
            player
        );

        clearAttemptState(
            player
        );

        /*
         * A player inside PlayerDeathEvent is still considered
         * dead.
         *
         * Respawn them first, then establish spectator state.
         */
        if (player.isDead()) {
            plugin.getServer()
                .getScheduler()
                .runTask(
                    plugin,
                    () ->
                        respawnIntoSpectator(
                            player,
                            null
                        )
                );

            return;
        }

        enterSpectator(
            player
        );
    }

    /**
     * Places a player into the run-ending spectator phase and
     * teleports them to the supplied focus location.
     *
     * For a real death, every online player receives the same
     * death-location snapshot.
     */
    public void prepareForCountdown(
        Player player,
        Location focusLocation
    ) {
        Objects.requireNonNull(
            player
        );

        Objects.requireNonNull(
            focusLocation
        );

        if (focusLocation.getWorld() == null) {
            throw new IllegalArgumentException(
                "Countdown focus location must belong to a world."
            );
        }

        Location target =
            focusLocation.clone();

        clearAttemptState(
            player
        );

        /*
         * The player who caused the attempt to end is still dead
         * while PlayerDeathEvent is being processed.
         *
         * They must:
         *
         * 1. respawn;
         * 2. become spectator;
         * 3. teleport BACK to their saved death location.
         *
         * This is especially important for Nether and End deaths,
         * because normal respawn may temporarily place them in
         * another dimension.
         */
        if (player.isDead()) {
            plugin.getServer()
                .getScheduler()
                .runTask(
                    plugin,
                    () ->
                        respawnIntoSpectator(
                            player,
                            target
                        )
                );

            return;
        }

        enterSpectator(
            player
        );

        teleportSpectator(
            player,
            target
        );
    }

    /*
     * ------------------------------------------------------------
     * DEATH RESPAWN
     * ------------------------------------------------------------
     */

    private void respawnIntoSpectator(
        Player player,
        Location focusLocation
    ) {
        if (!player.isOnline()) {
            return;
        }

        if (player.isDead()) {
            player.spigot()
                .respawn();
        }

        /*
         * Respawning modifies location, gamemode and several
         * internal player fields.
         *
         * Wait one additional tick before making our spectator
         * state authoritative.
         */
        plugin.getServer()
            .getScheduler()
            .runTask(
                plugin,
                () -> {
                    if (!player.isOnline()) {
                        return;
                    }

                    enterSpectator(
                        player
                    );

                    if (focusLocation != null) {
                        teleportSpectator(
                            player,
                            focusLocation
                        );
                    }
                }
            );
    }

    /*
     * ------------------------------------------------------------
     * SPECTATOR STATE
     * ------------------------------------------------------------
     */

    private void enterSpectator(
        Player player
    ) {
        player.closeInventory();

        player.setSpectatorTarget(
            null
        );

        player.setGameMode(
            GameMode.SPECTATOR
        );

        player.setFireTicks(
            0
        );

        player.setFallDistance(
            0.0f
        );

        player.setVelocity(
            new Vector(
                0.0,
                0.0,
                0.0
            )
        );
    }

    private void teleportSpectator(
        Player player,
        Location target
    ) {
        if (!player.isOnline()) {
            return;
        }

        Location destination =
            target.clone();

        boolean teleported =
            player.teleport(
                destination
            );

        if (!teleported) {
            plugin.getLogger().severe(
                "[Instigate Cafe] "
                    + "Failed to teleport "
                    + player.getName()
                    + " to the attempt-ending location in "
                    + destination
                        .getWorld()
                        .getName()
                    + "."
            );

            return;
        }

        /*
         * Teleports can affect velocity/fall state. Reassert the
         * spectator state after arrival.
         */
        player.setSpectatorTarget(
            null
        );

        player.setGameMode(
            GameMode.SPECTATOR
        );

        player.setFireTicks(
            0
        );

        player.setFallDistance(
            0.0f
        );

        player.setVelocity(
            new Vector(
                0.0,
                0.0,
                0.0
            )
        );
    }

    /*
     * ------------------------------------------------------------
     * NEXT ATTEMPT
     * ------------------------------------------------------------
     */

    /**
     * Performs the final clean reset immediately before a player
     * enters the next attempt.
     */
    public void prepareForNewAttempt(
        Player player,
        Location spawn
    ) {
        Objects.requireNonNull(
            player
        );

        Objects.requireNonNull(
            spawn
        );

        clearAttemptInventory(
            player
        );

        clearExperience(
            player
        );

        clearEffects(
            player
        );

        clearTransientState(
            player
        );

        resetVitals(
            player
        );

        player.setSpectatorTarget(
            null
        );

        boolean teleported =
            player.teleport(
                spawn
            );

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

    /*
     * ------------------------------------------------------------
     * ATTEMPT STATE CLEANUP
     * ------------------------------------------------------------
     */

    private void clearAttemptState(
        Player player
    ) {
        clearAttemptInventory(
            player
        );

        clearExperience(
            player
        );

        clearEffects(
            player
        );

        clearTransientState(
            player
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
        inventory.setHelmet(
            null
        );

        inventory.setChestplate(
            null
        );

        inventory.setLeggings(
            null
        );

        inventory.setBoots(
            null
        );

        inventory.setItemInOffHand(
            null
        );

        /*
         * Ender chests belong to the attempt as well.
         */
        player.getEnderChest()
            .clear();

        /*
         * Prevent an item held by the inventory cursor from
         * carrying into the next attempt.
         */
        player.setItemOnCursor(
            null
        );

        player.updateInventory();
    }

    private void clearExperience(
        Player player
    ) {
        player.setExp(
            0.0f
        );

        player.setLevel(
            0
        );

        player.setTotalExperience(
            0
        );
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
        player.setFireTicks(
            0
        );

        player.setFallDistance(
            0.0f
        );

        player.setFreezeTicks(
            0
        );

        player.setVelocity(
            new Vector(
                0.0,
                0.0,
                0.0
            )
        );

        player.setSprinting(
            false
        );

        player.setSneaking(
            false
        );
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