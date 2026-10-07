package dev.instigatehardcore.listener;

import dev.instigatehardcore.core.RunManager;
import dev.instigatehardcore.world.WorldSet;
import dev.instigatehardcore.world.WorldSetManager;

import org.bukkit.Location;
import org.bukkit.PortalType;
import org.bukkit.World;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import org.bukkit.event.entity.EntityPortalEvent;

import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class PortalRoutingListener implements Listener {

    private final JavaPlugin plugin;
    private final RunManager runManager;
    private final WorldSetManager worldSetManager;

    public PortalRoutingListener(
        JavaPlugin plugin,
        RunManager runManager,
        WorldSetManager worldSetManager
    ) {
        this.plugin =
            Objects.requireNonNull(plugin);

        this.runManager =
            Objects.requireNonNull(runManager);

        this.worldSetManager =
            Objects.requireNonNull(
                worldSetManager
            );
    }

    /**
     * Routes player portal travel entirely inside the
     * currently ACTIVE attempt.
     */
    @EventHandler(
        priority = EventPriority.HIGHEST,
        ignoreCancelled = true
    )
    public void onPlayerPortal(
        PlayerPortalEvent event
    ) {
        Player player =
            event.getPlayer();

        World sourceWorld =
            event.getFrom().getWorld();

        if (sourceWorld == null) {
            event.setCancelled(true);
            return;
        }

        /*
         * Players must not use portals while an attempt is
         * ENDING or RESETTING.
         */
        if (!runManager.isActive()) {
            if (isManagedWorld(sourceWorld)) {
                event.setCancelled(true);
            }

            return;
        }

        /*
         * Portal travel from STANDBY, RETIRED or lobby worlds
         * is never allowed.
         */
        if (
            !worldSetManager.isActiveWorld(
                sourceWorld
            )
        ) {
            if (isManagedWorld(sourceWorld)) {
                event.setCancelled(true);

                plugin.getLogger().fine(
                    "Blocked portal travel for "
                        + player.getName()
                        + " outside the ACTIVE WorldSet."
                );
            }

            return;
        }

        PlayerTeleportEvent.TeleportCause cause =
            event.getCause();

        if (
            cause
                == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
        ) {
            routePlayerNetherPortal(
                event,
                sourceWorld
            );

            return;
        }

        if (
            cause
                == PlayerTeleportEvent.TeleportCause.END_PORTAL
        ) {
            routePlayerEndPortal(
                event,
                player,
                sourceWorld
            );
        }

        /*
         * End gateways are intentionally left alone.
         *
         * They stay inside the same End world and therefore
         * require no cross-dimension remapping.
         */
    }

    private void routePlayerNetherPortal(
        PlayerPortalEvent event,
        World sourceWorld
    ) {
        WorldSet active =
            worldSetManager
                .getActiveWorldSet();

        World destinationWorld;

        if (
            sourceWorld.equals(
                active.overworld()
            )
        ) {
            destinationWorld =
                active.nether();
        } else if (
            sourceWorld.equals(
                active.nether()
            )
        ) {
            destinationWorld =
                active.overworld();
        } else {
            /*
             * Nether portals should not provide dimension travel
             * from the End.
             */
            event.setCancelled(true);
            return;
        }

        Location vanillaDestination =
            event.getTo();

        if (vanillaDestination == null) {
            event.setCancelled(true);
            return;
        }

        Location routedDestination =
            vanillaDestination.clone();

        /*
         * Paper has already applied vanilla Nether coordinate
         * scaling to this location.
         *
         * Replace only the destination world.
         */
        routedDestination.setWorld(
            destinationWorld
        );

        event.setTo(
            routedDestination
        );

        plugin.getLogger().fine(
            "[Instigate Cafe Hardcore] "
                + "Routed Nether portal: "
                + sourceWorld.getKey()
                + " -> "
                + destinationWorld.getKey()
        );
    }

    private void routePlayerEndPortal(
        PlayerPortalEvent event,
        Player player,
        World sourceWorld
    ) {
        WorldSet active =
            worldSetManager
                .getActiveWorldSet();

        /*
         * Overworld -> End
         */
        if (
            sourceWorld.equals(
                active.overworld()
            )
        ) {
            Location vanillaDestination =
                event.getTo();

            if (vanillaDestination == null) {
                event.setCancelled(true);
                return;
            }

            Location routedDestination =
                vanillaDestination.clone();

            routedDestination.setWorld(
                active.end()
            );

            event.setTo(
                routedDestination
            );

            plugin.getLogger().fine(
                "[Instigate Cafe Hardcore] "
                    + "Routed End portal into active End."
            );

            return;
        }

        /*
         * End -> player's current respawn location,
         * provided that respawn point still belongs to this
         * ACTIVE attempt.
         *
         * Otherwise return to the attempt's Overworld spawn.
         */
        if (
            sourceWorld.equals(
                active.end()
            )
        ) {
            Location destination =
                resolveEndExitDestination(
                    player,
                    active
                );

            event.setTo(
                destination
            );

            plugin.getLogger().fine(
                "[Instigate Cafe Hardcore] "
                    + "Routed End exit into active attempt."
            );

            return;
        }

        /*
         * There should be no End-portal transition originating
         * from our active Nether.
         */
        event.setCancelled(true);
    }

    private Location resolveEndExitDestination(
        Player player,
        WorldSet active
    ) {
        Location respawn =
            player.getRespawnLocation();

        /*
         * Preserve beds / respawn anchors when they belong to
         * the current attempt.
         */
        if (
            respawn != null
                && respawn.getWorld() != null
                && active.contains(
                    respawn.getWorld()
                )
        ) {
            return respawn.clone();
        }

        return active
            .getSpawnLocation()
            .clone();
    }

    /**
     * Non-player entities can travel through portals as well.
     *
     * Without this handler a pig, minecart, item, etc. could
     * potentially escape into one of the server's default
     * dimensions instead of the current attempt.
     */
    @EventHandler(
        priority = EventPriority.HIGHEST,
        ignoreCancelled = true
    )
    public void onEntityPortal(
        EntityPortalEvent event
    ) {
        Entity entity =
            event.getEntity();

        World sourceWorld =
            event.getFrom().getWorld();

        if (sourceWorld == null) {
            event.setCancelled(true);
            return;
        }

        if (!runManager.isActive()) {
            if (isManagedWorld(sourceWorld)) {
                event.setCancelled(true);
            }

            return;
        }

        if (
            !worldSetManager.isActiveWorld(
                sourceWorld
            )
        ) {
            if (isManagedWorld(sourceWorld)) {
                event.setCancelled(true);
            }

            return;
        }

        PortalType portalType =
            event.getPortalType();

        if (
            portalType
                == PortalType.NETHER
        ) {
            routeEntityNetherPortal(
                event,
                sourceWorld
            );

            return;
        }

        if (
            portalType
                == PortalType.ENDER
        ) {
            routeEntityEndPortal(
                event,
                sourceWorld
            );
        }

        /*
         * END_GATEWAY stays inside the current End and therefore
         * needs no remapping.
         */
    }

    private void routeEntityNetherPortal(
        EntityPortalEvent event,
        World sourceWorld
    ) {
        WorldSet active =
            worldSetManager
                .getActiveWorldSet();

        World destinationWorld;

        if (
            sourceWorld.equals(
                active.overworld()
            )
        ) {
            destinationWorld =
                active.nether();
        } else if (
            sourceWorld.equals(
                active.nether()
            )
        ) {
            destinationWorld =
                active.overworld();
        } else {
            event.setCancelled(true);
            return;
        }

        Location vanillaDestination =
            event.getTo();

        if (vanillaDestination == null) {
            event.setCancelled(true);
            return;
        }

        Location routedDestination =
            vanillaDestination.clone();

        routedDestination.setWorld(
            destinationWorld
        );

        event.setTo(
            routedDestination
        );
    }

    private void routeEntityEndPortal(
        EntityPortalEvent event,
        World sourceWorld
    ) {
        WorldSet active =
            worldSetManager
                .getActiveWorldSet();

        /*
         * Overworld -> End
         */
        if (
            sourceWorld.equals(
                active.overworld()
            )
        ) {
            Location vanillaDestination =
                event.getTo();

            if (vanillaDestination == null) {
                event.setCancelled(true);
                return;
            }

            Location routedDestination =
                vanillaDestination.clone();

            routedDestination.setWorld(
                active.end()
            );

            event.setTo(
                routedDestination
            );

            return;
        }

        /*
         * End -> active Overworld spawn
         */
        if (
            sourceWorld.equals(
                active.end()
            )
        ) {
            event.setTo(
                active
                    .getSpawnLocation()
                    .clone()
            );

            return;
        }

        event.setCancelled(true);
    }

    /**
     * Returns true for worlds controlled by Instigate Hardcore.
     *
     * This allows us to block portal travel from lobby,
     * STANDBY and RETIRED worlds without interfering with some
     * completely unrelated world another plugin might own.
     */
    private boolean isManagedWorld(
        World world
    ) {
        if (
            world.equals(
                worldSetManager
                    .getLobbyWorld()
            )
        ) {
            return true;
        }

        if (
            worldSetManager.isActiveWorld(
                world
            )
        ) {
            return true;
        }

        if (
            worldSetManager.isStandbyWorld(
                world
            )
        ) {
            return true;
        }

        return worldSetManager.isRetiredWorld(
            world
        );
    }
}