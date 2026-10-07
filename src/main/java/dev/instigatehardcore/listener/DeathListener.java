package dev.instigatehardcore.listener;

import dev.instigatehardcore.core.RunManager;
import dev.instigatehardcore.countdown.CountdownManager;
import dev.instigatehardcore.player.PlayerResetManager;
import dev.instigatehardcore.stats.StatsManager;
import dev.instigatehardcore.telemetry.PlayerDeathRecord;
import dev.instigatehardcore.telemetry.PlayerTelemetryManager;
import dev.instigatehardcore.world.WorldRotationManager;
import dev.instigatehardcore.world.WorldSetManager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

public final class DeathListener implements Listener {

    private final JavaPlugin plugin;

    private final RunManager runManager;
    private final StatsManager statsManager;
    private final PlayerTelemetryManager telemetryManager;

    private final CountdownManager countdownManager;
    private final PlayerResetManager playerResetManager;

    private final WorldSetManager worldSetManager;
    private final WorldRotationManager worldRotationManager;

    public DeathListener(
        JavaPlugin plugin,
        RunManager runManager,
        StatsManager statsManager,
        PlayerTelemetryManager telemetryManager,
        CountdownManager countdownManager,
        PlayerResetManager playerResetManager,
        WorldSetManager worldSetManager,
        WorldRotationManager worldRotationManager
    ) {
        this.plugin =
            Objects.requireNonNull(plugin);

        this.runManager =
            Objects.requireNonNull(runManager);

        this.statsManager =
            Objects.requireNonNull(statsManager);

        this.telemetryManager =
            Objects.requireNonNull(
                telemetryManager
            );

        this.countdownManager =
            Objects.requireNonNull(
                countdownManager
            );

        this.playerResetManager =
            Objects.requireNonNull(
                playerResetManager
            );

        this.worldSetManager =
            Objects.requireNonNull(
                worldSetManager
            );

        this.worldRotationManager =
            Objects.requireNonNull(
                worldRotationManager
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

        /*
         * Deaths outside the current ACTIVE WorldSet do not affect
         * the hardcore campaign.
         */
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
         * Suppress vanilla death output/drops immediately.
         */
        suppressVanillaDeath(
            event
        );

        /*
         * Once ENDING has begun, any additional deaths are ignored
         * for persistent statistics and attempt progression.
         */
        if (!runManager.isActive()) {
            return;
        }

        if (!runManager.beginEnding()) {
            return;
        }

        int attempt =
            statsManager
                .getCurrentAttempt();

        /*
         * Gameplay stops at the first fatal event, so stop every
         * ACTIVE playtime session before the countdown begins.
         */
        try {
            telemetryManager
                .endAttemptSessions(
                    attempt
                );
        } catch (
            IOException exception
        ) {
            plugin.getLogger().severe(
                "Failed to persist playtime when attempt #"
                    + attempt
                    + " ended."
            );

            exception.printStackTrace();
        }

        int totalDeaths =
            statsManager.getDeaths(
                player.getUniqueId()
            );

        try {
            statsManager.recordDeath(
                player.getUniqueId(),
                player.getName()
            );

            totalDeaths =
                statsManager.getDeaths(
                    player.getUniqueId()
                );
        } catch (
            IOException exception
        ) {
            plugin.getLogger().severe(
                "Failed to persist death statistics for "
                    + player.getName()
                    + "."
            );

            exception.printStackTrace();
        }

        try {
            telemetryManager.recordDeath(
                player.getUniqueId(),
                player.getName(),
                new PlayerDeathRecord(
                    attempt,
                    System.currentTimeMillis(),
                    deathCause,
                    deathMessage
                )
            );
        } catch (
            IOException exception
        ) {
            plugin.getLogger().severe(
                "Failed to persist structured death telemetry for "
                    + player.getName()
                    + "."
            );

            exception.printStackTrace();
        }

        announceAttemptDeath(
            player,
            attempt,
            totalDeaths,
            deathMessage
        );

        /*
         * Clears attempt state and makes everybody spectator for
         * the reset countdown.
         */
        playerResetManager
            .beginCountdownPhase();

        countdownManager.startCountdown(
            attempt,
            () -> {
                if (!runManager.beginResetting()) {
                    plugin.getLogger().severe(
                        "[Instigate Cafe Hardcore] "
                            + "Unable to transition attempt #"
                            + attempt
                            + " from ENDING to RESETTING."
                    );

                    return;
                }

                if (
                    !worldRotationManager
                        .rotateToStandby()
                ) {
                    plugin.getLogger().severe(
                        "[Instigate Cafe Hardcore] "
                            + "World rotation failed after attempt #"
                            + attempt
                            + "."
                    );
                }
            }
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

    private void announceAttemptDeath(
        Player player,
        int attempt,
        int totalDeaths,
        String deathMessage
    ) {
        Component divider =
            Component.text(
                "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━",
                NamedTextColor.DARK_GRAY
            );

        plugin.getServer().broadcast(
            divider
        );

        plugin.getServer().broadcast(
            Component.text(
                "INSTIGATE CAFE HARDCORE",
                NamedTextColor.GOLD
            ).decorate(
                TextDecoration.BOLD
            )
        );

        plugin.getServer().broadcast(
            Component.text(
                "Attempt #"
                    + attempt
                    + " has ended",
                NamedTextColor.RED
            )
        );

        plugin.getServer().broadcast(
            Component.empty()
        );

        plugin.getServer().broadcast(
            Component.text(
                deathMessage,
                NamedTextColor.WHITE
            )
        );

        plugin.getServer().broadcast(
            Component.text()
                .append(
                    Component.text(
                        player.getName(),
                        NamedTextColor.RED
                    )
                )
                .append(
                    Component.text(
                        " now has "
                            + totalDeaths
                            + " total death"
                            + (
                                totalDeaths == 1
                                    ? ""
                                    : "s"
                            )
                            + ".",
                        NamedTextColor.GRAY
                    )
                )
                .build()
        );

        plugin.getServer().broadcast(
            Component.empty()
        );

        plugin.getServer().broadcast(
            Component.text(
                "Next attempt in "
                    + countdownManager.getDurationSeconds()
                    + " seconds...",
                NamedTextColor.GRAY
            )
        );

        plugin.getServer().broadcast(
            divider
        );
    }

    /**
     * Produces a stable display category suitable for statistics.
     *
     * When the final damage directly came from an entity, prefer
     * that entity over the generic Bukkit damage cause.
     */
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

            if (damager instanceof Projectile projectile) {
                ProjectileSource shooter =
                    projectile.getShooter();

                if (shooter instanceof Player playerShooter) {
                    return playerShooter.getName();
                }

                if (shooter instanceof Entity entityShooter) {
                    return formatEnumName(
                        entityShooter
                            .getType()
                            .name()
                    );
                }
            }

            if (damager instanceof Player playerDamager) {
                return playerDamager.getName();
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

        for (String word : words) {
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