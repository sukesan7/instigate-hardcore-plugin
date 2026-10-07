package dev.instigatehardcore.listener;

import dev.instigatehardcore.core.RunManager;

import dev.instigatehardcore.participation.AttemptAdmissionPolicy;
import dev.instigatehardcore.participation.AttemptParticipantManager;

import dev.instigatehardcore.player.PlayerResetManager;

import dev.instigatehardcore.scoreboard.HardcoreScoreboardManager;

import dev.instigatehardcore.stats.StatsManager;

import dev.instigatehardcore.telemetry.PlayerTelemetryManager;

import dev.instigatehardcore.ui.InstigateTheme;

import dev.instigatehardcore.world.WorldSet;
import dev.instigatehardcore.world.WorldSetManager;

import net.kyori.adventure.text.Component;

import org.bukkit.GameMode;
import org.bukkit.Location;

import org.bukkit.entity.Player;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import org.bukkit.event.player.PlayerJoinEvent;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;

import java.util.Objects;

public final class PlayerJoinListener implements Listener {

    /*
     * Give Paper a short window to finish restoring the player's
     * saved world, location and gamemode before we enforce the
     * campaign state.
     */
    private static final long INITIAL_JOIN_DELAY_TICKS =
        5L;

    /*
     * After placing a player into the ACTIVE attempt, verify the
     * result repeatedly for a short period.
     *
     * This protects against Paper or another login-stage operation
     * restoring stale spectator/location state after our first
     * placement.
     */
    private static final long VERIFICATION_DELAY_TICKS =
        10L;

    private static final int MAX_VERIFICATION_ATTEMPTS =
        4;

    private final JavaPlugin plugin;

    private final StatsManager statsManager;

    private final HardcoreScoreboardManager scoreboardManager;

    private final RunManager runManager;

    private final PlayerResetManager playerResetManager;
    private final WorldSetManager worldSetManager;

    private final AttemptParticipantManager participantManager;
    private final PlayerTelemetryManager telemetryManager;

    private final AttemptAdmissionPolicy admissionPolicy;

    public PlayerJoinListener(
        JavaPlugin plugin,
        StatsManager statsManager,
        HardcoreScoreboardManager scoreboardManager,
        RunManager runManager,
        PlayerResetManager playerResetManager,
        WorldSetManager worldSetManager,
        AttemptParticipantManager participantManager,
        PlayerTelemetryManager telemetryManager,
        boolean allowLateJoiners
    ) {
        this.plugin =
            Objects.requireNonNull(
                plugin
            );

        this.statsManager =
            Objects.requireNonNull(
                statsManager
            );

        /*
         * May be null when scoreboard.enabled=false.
         */
        this.scoreboardManager =
            scoreboardManager;

        this.runManager =
            Objects.requireNonNull(
                runManager
            );

        this.playerResetManager =
            Objects.requireNonNull(
                playerResetManager
            );

        this.worldSetManager =
            Objects.requireNonNull(
                worldSetManager
            );

        this.participantManager =
            Objects.requireNonNull(
                participantManager
            );

        this.telemetryManager =
            Objects.requireNonNull(
                telemetryManager
            );

        this.admissionPolicy =
            new AttemptAdmissionPolicy(
                allowLateJoiners
            );
    }

    /*
     * ------------------------------------------------------------
     * JOIN
     * ------------------------------------------------------------
     */

    @EventHandler
    public void onPlayerJoin(
        PlayerJoinEvent event
    ) {
        Player player =
            event.getPlayer();

        registerPlayerStats(
            player
        );

        if (scoreboardManager != null) {
            scoreboardManager.assign(
                player
            );

            scoreboardManager.refresh();
        }

        /*
         * Do not immediately fight Paper's login restoration.
         *
         * Wait a few ticks, then make the hardcore campaign state
         * authoritative.
         */
        plugin.getServer()
            .getScheduler()
            .runTaskLater(
                plugin,
                () -> placePlayer(
                    player
                ),
                INITIAL_JOIN_DELAY_TICKS
            );
    }

    private void registerPlayerStats(
        Player player
    ) {
        try {
            statsManager.ensurePlayer(
                player.getUniqueId(),
                player.getName()
            );
        } catch (
            IOException exception
        ) {
            plugin.getLogger().severe(
                "Failed to persist player data for "
                    + player.getName()
                    + "."
            );

            exception.printStackTrace();
        }
    }

    /*
     * ------------------------------------------------------------
     * CAMPAIGN PLACEMENT
     * ------------------------------------------------------------
     */

    private void placePlayer(
        Player player
    ) {
        if (!player.isOnline()) {
            return;
        }

        /*
         * ENDING and RESETTING are not joinable gameplay states.
         */
        if (!runManager.isActive()) {
            sendToSafetyLobby(
                player
            );

            return;
        }

        int attempt =
            statsManager
                .getCurrentAttempt();

        boolean existingParticipant =
            participantManager
                .hasParticipant(
                    attempt,
                    player.getUniqueId()
                );

        if (
            !mayEnterCurrentAttempt(
                attempt,
                existingParticipant
            )
        ) {
            notifyLateJoinBlocked(
                player,
                attempt
            );

            sendToSafetyLobby(
                player
            );

            return;
        }

        /*
         * A brand-new participant receives the full clean-entry
         * reset once.
         *
         * Someone reconnecting to an attempt they already played
         * must NOT have their inventory wiped.
         */
        boolean needsFreshReset =
            !existingParticipant;

        enforceActivePlacement(
            player,
            attempt,
            needsFreshReset,
            0
        );
    }

    /*
     * ------------------------------------------------------------
     * ACTIVE PLACEMENT
     * ------------------------------------------------------------
     */

    private void enforceActivePlacement(
        Player player,
        int expectedAttempt,
        boolean needsFreshReset,
        int verificationAttempt
    ) {
        if (!player.isOnline()) {
            return;
        }

        /*
         * The attempt may have ended while this delayed task was
         * waiting.
         */
        if (!runManager.isActive()) {
            sendToSafetyLobby(
                player
            );

            return;
        }

        int currentAttempt =
            statsManager
                .getCurrentAttempt();

        /*
         * If rotation completed while we were verifying login,
         * evaluate the player against the newly-active attempt
         * instead of forcing them into an obsolete attempt.
         */
        if (
            currentAttempt
                != expectedAttempt
        ) {
            placePlayer(
                player
            );

            return;
        }

        WorldSet activeWorldSet =
            worldSetManager
                .getActiveWorldSet();

        if (activeWorldSet == null) {
            plugin.getLogger().severe(
                "[Instigate Cafe] "
                    + "Unable to place "
                    + player.getName()
                    + " into attempt #"
                    + expectedAttempt
                    + " because the ACTIVE WorldSet is missing."
            );

            sendToSafetyLobby(
                player
            );

            return;
        }

        boolean inActiveWorld =
            worldSetManager
                .isActiveWorld(
                    player.getWorld()
                );

        if (!inActiveWorld) {
            boolean moved =
                moveIntoActiveAttempt(
                    player,
                    activeWorldSet,
                    needsFreshReset
                );

            if (!moved) {
                scheduleVerificationOrFail(
                    player,
                    expectedAttempt,
                    needsFreshReset,
                    verificationAttempt
                );

                return;
            }

            /*
             * The full reset must only happen once.
             *
             * Any later corrective teleport during verification
             * must preserve the player's newly-started attempt
             * state.
             */
            needsFreshReset =
                false;
        }

        /*
         * If placement succeeded, establish all four conditions
         * required for a valid participant:
         *
         * - inside ACTIVE WorldSet
         * - Survival
         * - persistent participation
         * - active telemetry session
         */
        if (
            isActiveParticipantLocation(
                player,
                expectedAttempt
            )
        ) {
            normalizeActivePlayer(
                player
            );

            recordParticipation(
                player,
                expectedAttempt
            );

            beginTelemetrySession(
                player,
                expectedAttempt
            );
        }

        /*
         * Even after a successful first placement, verify it again.
         *
         * We do not assume the first teleport/gamemode assignment
         * survives the rest of Paper's login restoration.
         */
        if (
            verificationAttempt
                < MAX_VERIFICATION_ATTEMPTS
        ) {
            scheduleVerification(
                player,
                expectedAttempt,
                needsFreshReset,
                verificationAttempt
                    + 1
            );
        }
    }

    private boolean moveIntoActiveAttempt(
        Player player,
        WorldSet activeWorldSet,
        boolean needsFreshReset
    ) {
        Location activeSpawn =
            activeWorldSet
                .getSpawnLocation();

        try {
            if (needsFreshReset) {
                /*
                 * First entry into this attempt:
                 *
                 * clear state + teleport using the same reset path
                 * used when a new attempt begins.
                 */
                playerResetManager
                    .prepareForNewAttempt(
                        player,
                        activeSpawn
                    );
            } else {
                /*
                 * Existing participant or corrective retry:
                 *
                 * preserve inventory and gameplay state.
                 *
                 * Paper only permits setSpectatorTarget() while
                 * the player is actually in spectator mode.
                 */
                if (
                    player.getGameMode()
                        == GameMode.SPECTATOR
                ) {
                    player.setSpectatorTarget(
                        null
                    );
                }

                boolean teleported =
                    player.teleport(
                        activeSpawn
                    );

                if (!teleported) {
                    plugin.getLogger().warning(
                        "[Instigate Cafe] "
                            + "Corrective ACTIVE teleport failed for "
                            + player.getName()
                            + "."
                    );

                    return false;
                }

                player.setGameMode(
                    GameMode.SURVIVAL
                );
            }

            return worldSetManager
                .isActiveWorld(
                    player.getWorld()
                );
        } catch (
            RuntimeException exception
        ) {
            plugin.getLogger().severe(
                "[Instigate Cafe] "
                    + "Failed to move "
                    + player.getName()
                    + " into the ACTIVE attempt."
            );

            exception.printStackTrace();

            return false;
        }
    }

    /*
     * ------------------------------------------------------------
     * VERIFICATION
     * ------------------------------------------------------------
     */

    private void scheduleVerification(
        Player player,
        int expectedAttempt,
        boolean needsFreshReset,
        int verificationAttempt
    ) {
        plugin.getServer()
            .getScheduler()
            .runTaskLater(
                plugin,
                () ->
                    verifyActivePlacement(
                        player,
                        expectedAttempt,
                        needsFreshReset,
                        verificationAttempt
                    ),
                VERIFICATION_DELAY_TICKS
            );
    }

    private void verifyActivePlacement(
        Player player,
        int expectedAttempt,
        boolean needsFreshReset,
        int verificationAttempt
    ) {
        if (!player.isOnline()) {
            return;
        }

        if (!runManager.isActive()) {
            sendToSafetyLobby(
                player
            );

            return;
        }

        int currentAttempt =
            statsManager
                .getCurrentAttempt();

        if (
            currentAttempt
                != expectedAttempt
        ) {
            /*
             * A world rotation occurred while we were checking.
             * Re-enter the normal placement path for the new run.
             */
            placePlayer(
                player
            );

            return;
        }

        boolean existingParticipant =
            participantManager
                .hasParticipant(
                    expectedAttempt,
                    player.getUniqueId()
                );

        if (
            !mayEnterCurrentAttempt(
                expectedAttempt,
                existingParticipant
            )
        ) {
            sendToSafetyLobby(
                player
            );

            return;
        }

        boolean valid =
            isActiveParticipantLocation(
                player,
                expectedAttempt
            );

        if (valid) {
            normalizeActivePlayer(
                player
            );

            recordParticipation(
                player,
                expectedAttempt
            );

            beginTelemetrySession(
                player,
                expectedAttempt
            );

            /*
             * Continue verification until the short verification
             * window has finished.
             *
             * If Paper changes location or gamemode after this
             * tick, a later pass repairs it.
             */
            if (
                verificationAttempt
                    < MAX_VERIFICATION_ATTEMPTS
            ) {
                scheduleVerification(
                    player,
                    expectedAttempt,
                    false,
                    verificationAttempt
                        + 1
                );
            }

            return;
        }

        /*
         * Something restored the player outside ACTIVE.
         *
         * Correct it.
         *
         * If the player has already been recorded as a
         * participant, never wipe them again.
         */
        boolean shouldFreshReset =
            needsFreshReset
                && !existingParticipant;

        enforceActivePlacement(
            player,
            expectedAttempt,
            shouldFreshReset,
            verificationAttempt
        );
    }

    private void scheduleVerificationOrFail(
        Player player,
        int expectedAttempt,
        boolean needsFreshReset,
        int verificationAttempt
    ) {
        if (
            verificationAttempt
                >= MAX_VERIFICATION_ATTEMPTS
        ) {
            plugin.getLogger().severe(
                "[Instigate Cafe] "
                    + "Unable to establish ACTIVE placement for "
                    + player.getName()
                    + " after "
                    + MAX_VERIFICATION_ATTEMPTS
                    + " verification attempts."
            );

            player.sendMessage(
                InstigateTheme.chat(
                    InstigateTheme.error(
                        "Unable to place you into the current attempt. "
                            + "You were moved to the safety lobby."
                    )
                )
            );

            sendToSafetyLobby(
                player
            );

            return;
        }

        scheduleVerification(
            player,
            expectedAttempt,
            needsFreshReset,
            verificationAttempt
                + 1
        );
    }

    /*
     * ------------------------------------------------------------
     * ATTEMPT ADMISSION
     * ------------------------------------------------------------
     */

    private boolean mayEnterCurrentAttempt(
        int attempt,
        boolean existingParticipant
    ) {
        return admissionPolicy.mayEnter(
            existingParticipant,
            participantManager
                .getParticipantCount(
                    attempt
                )
        );
    }

    private void notifyLateJoinBlocked(
        Player player,
        int attempt
    ) {
        player.sendMessage(
            InstigateTheme.chat(
                Component.text()
                    .append(
                        InstigateTheme.attempt(
                            attempt
                        )
                    )
                    .append(
                        InstigateTheme.secondary(
                            " is already in progress. "
                        )
                    )
                    .append(
                        InstigateTheme.text(
                            "You'll join the next attempt."
                        )
                    )
                    .build()
            )
        );

        plugin.getLogger().info(
            "[Instigate Cafe] "
                + player.getName()
                + " was held out of attempt #"
                + attempt
                + " because late joining is disabled."
        );
    }

    /*
     * ------------------------------------------------------------
     * PARTICIPATION
     * ------------------------------------------------------------
     */

    private void recordParticipation(
        Player player,
        int attempt
    ) {
        if (
            !isActiveParticipantLocation(
                player,
                attempt
            )
        ) {
            return;
        }

        try {
            boolean firstParticipation =
                participantManager
                    .recordParticipant(
                        attempt,
                        player.getUniqueId(),
                        player.getName()
                    );

            if (firstParticipation) {
                plugin.getLogger().info(
                    "[Instigate Cafe] "
                        + player.getName()
                        + " joined attempt #"
                        + attempt
                        + " as a participant."
                );
            }
        } catch (
            IOException exception
        ) {
            plugin.getLogger().severe(
                "Failed to record attempt participation for "
                    + player.getName()
                    + "."
            );

            exception.printStackTrace();
        }
    }

    /*
     * ------------------------------------------------------------
     * TELEMETRY
     * ------------------------------------------------------------
     */

    private void beginTelemetrySession(
        Player player,
        int attempt
    ) {
        if (
            !isActiveParticipantLocation(
                player,
                attempt
            )
        ) {
            return;
        }

        telemetryManager.beginSession(
            attempt,
            player.getUniqueId(),
            player.getName()
        );
    }

    /*
     * ------------------------------------------------------------
     * ACTIVE STATE
     * ------------------------------------------------------------
     */

    private boolean isActiveParticipantLocation(
        Player player,
        int expectedAttempt
    ) {
        if (
            !player.isOnline()
                || !runManager.isActive()
        ) {
            return false;
        }

        if (
            statsManager
                .getCurrentAttempt()
                != expectedAttempt
        ) {
            return false;
        }

        WorldSet active =
            worldSetManager
                .getActiveWorldSet();

        return active != null
            && active.attemptNumber()
                == expectedAttempt
            && worldSetManager
                .isActiveWorld(
                    player.getWorld()
                );
    }

    private void normalizeActivePlayer(
        Player player
    ) {
        /*
         * Paper only permits spectator-target modification while
         * the player is actually in SPECTATOR mode.
         */
        if (
            player.getGameMode()
                == GameMode.SPECTATOR
        ) {
            player.setSpectatorTarget(
                null
            );
        }

        if (
            player.getGameMode()
                != GameMode.SURVIVAL
        ) {
            plugin.getLogger().info(
                "[Instigate Cafe] "
                    + "Restoring "
                    + player.getName()
                    + " to SURVIVAL in the active attempt."
            );

            player.setGameMode(
                GameMode.SURVIVAL
            );
        }
    }

    /*
     * ------------------------------------------------------------
     * SAFETY LOBBY
     * ------------------------------------------------------------
     */

    private void sendToSafetyLobby(
        Player player
    ) {
        if (!player.isOnline()) {
            return;
        }

        /*
         * A player outside ACTIVE gameplay must not retain an
         * ACTIVE telemetry session.
         */
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
                    + "."
            );

            exception.printStackTrace();
        }

        playerResetManager
            .prepareForCountdown(
                player
            );

        plugin.getServer()
            .getScheduler()
            .runTask(
                plugin,
                () -> {
                    if (!player.isOnline()) {
                        return;
                    }

                    Location lobbySpawn =
                        worldSetManager
                            .getLobbyWorld()
                            .getSpawnLocation()
                            .clone()
                            .add(
                                0.5,
                                0.0,
                                0.5
                            );

                    boolean teleported =
                        player.teleport(
                            lobbySpawn
                        );

                    if (!teleported) {
                        plugin.getLogger().severe(
                            "Failed to move "
                                + player.getName()
                                + " to the safety lobby."
                        );

                        return;
                    }

                    /*
                     * Gamemode must be established before touching
                     * the spectator target.
                     */
                    if (
                        player.getGameMode()
                            != GameMode.SPECTATOR
                    ) {
                        player.setGameMode(
                            GameMode.SPECTATOR
                        );
                    }

                    player.setSpectatorTarget(
                        null
                    );
                }
            );
    }
}