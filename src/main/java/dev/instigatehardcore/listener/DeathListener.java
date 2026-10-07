package dev.instigatehardcore.listener;

import dev.instigatehardcore.core.AttemptEndManager;
import dev.instigatehardcore.world.WorldSetManager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

import org.bukkit.projectiles.ProjectileSource;

import java.util.Locale;
import java.util.Objects;

public final class DeathListener implements Listener {

    private final WorldSetManager worldSetManager;
    private final AttemptEndManager attemptEndManager;

    public DeathListener(
        WorldSetManager worldSetManager,
        AttemptEndManager attemptEndManager
    ) {
        this.worldSetManager =
            Objects.requireNonNull(
                worldSetManager
            );

        this.attemptEndManager =
            Objects.requireNonNull(
                attemptEndManager
            );
    }

    @EventHandler(
        priority = EventPriority.HIGHEST
    )
    public void onPlayerDeath(
        PlayerDeathEvent event
    ) {
        Player player =
            event.getEntity();

        if (
            !worldSetManager.isActiveWorld(
                player.getWorld()
            )
        ) {
            return;
        }

        Component originalDeathComponent =
            event.deathMessage();

        String deathMessage =
            originalDeathComponent == null
                ? player.getName() + " died"
                : PlainTextComponentSerializer
                    .plainText()
                    .serialize(
                        originalDeathComponent
                    );

        String deathCause =
            determineDeathCause(
                player
            );

        /*
         * Shared-hardcore attempts never retain vanilla death
         * drops, XP or death chat.
         */
        suppressVanillaDeath(
            event
        );

        /*
         * AttemptEndManager atomically decides whether this is
         * actually the run-ending death.
         *
         * Additional PlayerDeathEvents during ENDING are ignored.
         */
        attemptEndManager
            .endFromPlayerDeath(
                player,
                deathMessage,
                deathCause
            );
    }

    private void suppressVanillaDeath(
        PlayerDeathEvent event
    ) {
        event.deathMessage(
            null
        );

        event.getDrops()
            .clear();

        event.setDroppedExp(
            0
        );
    }

    private String determineDeathCause(
        Player player
    ) {
        EntityDamageEvent damageEvent =
            player.getLastDamageCause();

        if (damageEvent == null) {
            return "Unknown";
        }

        if (
            damageEvent
                instanceof EntityDamageByEntityEvent byEntity
        ) {
            Entity damager =
                byEntity.getDamager();

            if (
                damager
                    instanceof Projectile projectile
            ) {
                ProjectileSource shooter =
                    projectile.getShooter();

                if (
                    shooter
                        instanceof Player playerShooter
                ) {
                    return playerShooter
                        .getName();
                }

                if (
                    shooter
                        instanceof Entity entityShooter
                ) {
                    return formatEnumName(
                        entityShooter
                            .getType()
                            .name()
                    );
                }
            }

            if (
                damager
                    instanceof Player playerDamager
            ) {
                return playerDamager
                    .getName();
            }

            return formatEnumName(
                damager
                    .getType()
                    .name()
            );
        }

        return formatEnumName(
            damageEvent
                .getCause()
                .name()
        );
    }

    private String formatEnumName(
        String value
    ) {
        String[] words =
            value
                .toLowerCase(
                    Locale.ROOT
                )
                .split("_");

        StringBuilder result =
            new StringBuilder();

        for (
            String word :
            words
        ) {
            if (word.isEmpty()) {
                continue;
            }

            if (!result.isEmpty()) {
                result.append(
                    ' '
                );
            }

            result.append(
                Character.toUpperCase(
                    word.charAt(0)
                )
            );

            if (word.length() > 1) {
                result.append(
                    word.substring(1)
                );
            }
        }

        return result.isEmpty()
            ? "Unknown"
            : result.toString();
    }
}