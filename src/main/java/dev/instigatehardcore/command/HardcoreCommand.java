package dev.instigatehardcore.command;

import dev.instigatehardcore.core.AttemptEndManager;
import dev.instigatehardcore.core.RunManager;

import dev.instigatehardcore.countdown.CountdownManager;

import dev.instigatehardcore.participation.AttemptParticipant;
import dev.instigatehardcore.participation.AttemptParticipantManager;

import dev.instigatehardcore.stats.PlayerStats;
import dev.instigatehardcore.stats.StatsManager;

import dev.instigatehardcore.telemetry.PlayerDeathRecord;
import dev.instigatehardcore.telemetry.PlayerTelemetryManager;

import dev.instigatehardcore.ui.InstigateTheme;

import dev.instigatehardcore.world.WorldCleanupManager;
import dev.instigatehardcore.world.WorldRotationManager;
import dev.instigatehardcore.world.WorldRotationPhase;
import dev.instigatehardcore.world.WorldRotationState;
import dev.instigatehardcore.world.WorldSet;
import dev.instigatehardcore.world.WorldSetManager;
import dev.instigatehardcore.world.WorldStateStore;

import net.kyori.adventure.text.Component;

import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;

import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import org.bukkit.Bukkit;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import org.bukkit.entity.Player;

import java.io.IOException;

import java.time.Duration;
import java.time.Instant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class HardcoreCommand
    implements CommandExecutor {

    private static final long RESET_CONFIRMATION_WINDOW_MILLIS =
        30_000L;

    private final RunManager runManager;
    private final StatsManager statsManager;

    private final AttemptParticipantManager participantManager;
    private final PlayerTelemetryManager telemetryManager;

    private final WorldSetManager worldSetManager;
    private final WorldRotationManager worldRotationManager;
    private final WorldCleanupManager worldCleanupManager;

    private final CountdownManager countdownManager;
    private final WorldStateStore worldStateStore;

    private final AttemptEndManager attemptEndManager;

    private final Map<String, ResetConfirmation> resetConfirmations =
        new HashMap<>();

    public HardcoreCommand(
        RunManager runManager,
        StatsManager statsManager,
        AttemptParticipantManager participantManager,
        PlayerTelemetryManager telemetryManager,
        WorldSetManager worldSetManager,
        WorldRotationManager worldRotationManager,
        WorldCleanupManager worldCleanupManager,
        CountdownManager countdownManager,
        WorldStateStore worldStateStore,
        AttemptEndManager attemptEndManager
    ) {
        this.runManager =
            Objects.requireNonNull(
                runManager
            );

        this.statsManager =
            Objects.requireNonNull(
                statsManager
            );

        this.participantManager =
            Objects.requireNonNull(
                participantManager
            );

        this.telemetryManager =
            Objects.requireNonNull(
                telemetryManager
            );

        this.worldSetManager =
            Objects.requireNonNull(
                worldSetManager
            );

        this.worldRotationManager =
            Objects.requireNonNull(
                worldRotationManager
            );

        this.worldCleanupManager =
            Objects.requireNonNull(
                worldCleanupManager
            );

        this.countdownManager =
            Objects.requireNonNull(
                countdownManager
            );

        this.worldStateStore =
            Objects.requireNonNull(
                worldStateStore
            );

        this.attemptEndManager =
            Objects.requireNonNull(
                attemptEndManager
            );
    }

    @Override
    public boolean onCommand(
        CommandSender sender,
        Command command,
        String label,
        String[] args
    ) {
        if (args.length == 0) {
            sendHelp(
                sender
            );

            return true;
        }

        String subcommand =
            args[0].toLowerCase(
                Locale.ROOT
            );

        return switch (subcommand) {
            case "help" -> {
                sendHelp(
                    sender
                );

                yield true;
            }

            case "status" -> {
                sendStatus(
                    sender
                );

                yield true;
            }

            case "stats" -> {
                handleStats(
                    sender,
                    args
                );

                yield true;
            }

            case "deaths" -> {
                sendDeaths(
                    sender
                );

                yield true;
            }

            case "reset" -> {
                if (
                    !sender.hasPermission(
                        "instigatehardcore.admin"
                    )
                ) {
                    sendNoPermission(
                        sender
                    );

                    yield true;
                }

                handleReset(
                    sender,
                    args
                );

                yield true;
            }

            case "worlds" -> {
                if (
                    !sender.hasPermission(
                        "instigatehardcore.admin"
                    )
                ) {
                    sendNoPermission(
                        sender
                    );

                    yield true;
                }

                sendWorlds(
                    sender
                );

                yield true;
            }

            case "debug" -> {
                if (
                    !sender.hasPermission(
                        "instigatehardcore.debug"
                    )
                ) {
                    sendNoPermission(
                        sender
                    );

                    yield true;
                }

                sendDebug(
                    sender
                );

                yield true;
            }

            default -> {
                sendUnknownCommand(
                    sender,
                    subcommand
                );

                yield true;
            }
        };
    }

    /*
     * ------------------------------------------------------------
     * RESET
     * ------------------------------------------------------------
     */

    private void handleReset(
        CommandSender sender,
        String[] args
    ) {
        if (args.length == 1) {
            requestResetConfirmation(
                sender
            );

            return;
        }

        if (
            args.length == 2
                && args[1].equalsIgnoreCase(
                    "confirm"
                )
        ) {
            confirmReset(
                sender
            );

            return;
        }

        sender.sendMessage(
            InstigateTheme.chat(
                InstigateTheme.error(
                    "Usage: /hc reset [confirm]"
                )
            )
        );
    }

    private void requestResetConfirmation(
        CommandSender sender
    ) {
        if (!runManager.isActive()) {
            sender.sendMessage(
                InstigateTheme.chat(
                    Component.text()
                        .append(
                            InstigateTheme.error(
                                "The current attempt cannot be reset while state is "
                            )
                        )
                        .append(
                            Component.text(
                                formatEnumLabel(
                                    runManager
                                        .getState()
                                        .name()
                                ),
                                InstigateTheme.TEXT
                            )
                        )
                        .append(
                            InstigateTheme.error(
                                "."
                            )
                        )
                        .build()
                )
            );

            return;
        }

        int attempt =
            statsManager
                .getCurrentAttempt();

        long expiresAt =
            System.currentTimeMillis()
                + RESET_CONFIRMATION_WINDOW_MILLIS;

        resetConfirmations.put(
            resetConfirmationKey(
                sender
            ),
            new ResetConfirmation(
                attempt,
                expiresAt
            )
        );

        sender.sendMessage(
            Component.empty()
        );

        sender.sendMessage(
            InstigateTheme.divider()
        );

        sender.sendMessage(
            InstigateTheme.brand()
        );

        sender.sendMessage(
            Component.text(
                "Administrative Reset",
                InstigateTheme.ERROR
            ).decorate(
                TextDecoration.BOLD
            )
        );

        sender.sendMessage(
            Component.empty()
        );

        sender.sendMessage(
            Component.text()
                .append(
                    InstigateTheme.secondary(
                        "This will permanently end "
                    )
                )
                .append(
                    InstigateTheme.attempt(
                        attempt
                    )
                )
                .append(
                    InstigateTheme.secondary(
                        "."
                    )
                )
                .build()
        );

        sender.sendMessage(
            InstigateTheme.muted(
                "No player death will be recorded."
            )
        );

        sender.sendMessage(
            Component.empty()
        );

        Component confirmationCommand =
            Component.text(
                "/hc reset confirm",
                InstigateTheme.PURPLE
            )
                .decorate(
                    TextDecoration.BOLD
                )
                .hoverEvent(
                    HoverEvent.showText(
                        InstigateTheme.secondary(
                            "Click to place the confirmation command in chat."
                        )
                    )
                )
                .clickEvent(
                    ClickEvent.suggestCommand(
                        "/hc reset confirm"
                    )
                );

        sender.sendMessage(
            Component.text()
                .append(
                    InstigateTheme.secondary(
                        "Run "
                    )
                )
                .append(
                    confirmationCommand
                )
                .append(
                    InstigateTheme.secondary(
                        " within 30 seconds."
                    )
                )
                .build()
        );

        sender.sendMessage(
            InstigateTheme.divider()
        );

        sender.sendMessage(
            Component.empty()
        );
    }

    private void confirmReset(
        CommandSender sender
    ) {
        String key =
            resetConfirmationKey(
                sender
            );

        ResetConfirmation confirmation =
            resetConfirmations.remove(
                key
            );

        if (confirmation == null) {
            sender.sendMessage(
                InstigateTheme.chat(
                    InstigateTheme.error(
                        "No active reset confirmation. Run /hc reset first."
                    )
                )
            );

            return;
        }

        long now =
            System.currentTimeMillis();

        if (
            now
                > confirmation.expiresAt()
        ) {
            sender.sendMessage(
                InstigateTheme.chat(
                    InstigateTheme.error(
                        "Reset confirmation expired. Run /hc reset again."
                    )
                )
            );

            return;
        }

        if (!runManager.isActive()) {
            sender.sendMessage(
                InstigateTheme.chat(
                    InstigateTheme.error(
                        "The attempt is no longer active."
                    )
                )
            );

            return;
        }

        int currentAttempt =
            statsManager
                .getCurrentAttempt();

        if (
            confirmation.attempt()
                != currentAttempt
        ) {
            sender.sendMessage(
                InstigateTheme.chat(
                    Component.text()
                        .append(
                            InstigateTheme.error(
                                "That confirmation was for Attempt #"
                                    + confirmation.attempt()
                                    + ", but the server is now on "
                            )
                        )
                        .append(
                            InstigateTheme.attempt(
                                currentAttempt
                            )
                        )
                        .append(
                            InstigateTheme.error(
                                ". Run /hc reset again."
                            )
                        )
                        .build()
                )
            );

            return;
        }

        boolean ended =
            attemptEndManager
                .endFromAdminReset(
                    sender
                );

        if (!ended) {
            sender.sendMessage(
                InstigateTheme.chat(
                    InstigateTheme.error(
                        "The attempt could not be reset because it is no longer active."
                    )
                )
            );
        }
    }

    private String resetConfirmationKey(
        CommandSender sender
    ) {
        if (
            sender
                instanceof Player player
        ) {
            return "player:"
                + player
                    .getUniqueId();
        }

        return "sender:"
            + sender
                .getName()
                .toLowerCase(
                    Locale.ROOT
                );
    }

    /*
     * ------------------------------------------------------------
     * PLAYER STATS
     * ------------------------------------------------------------
     */

    private void handleStats(
        CommandSender sender,
        String[] args
    ) {
        if (args.length == 1) {
            if (
                !(sender instanceof Player player)
            ) {
                sender.sendMessage(
                    InstigateTheme.chat(
                        InstigateTheme.error(
                            "Console must specify a player: /hc stats <player>"
                        )
                    )
                );

                return;
            }

            sendPlayerStats(
                sender,
                player.getUniqueId(),
                player.getName()
            );

            return;
        }

        if (args.length > 2) {
            sender.sendMessage(
                InstigateTheme.chat(
                    InstigateTheme.error(
                        "Usage: /hc stats [player]"
                    )
                )
            );

            return;
        }

        Optional<PlayerStats> target =
            findPlayerStats(
                args[1]
            );

        if (target.isEmpty()) {
            sender.sendMessage(
                InstigateTheme.chat(
                    Component.text()
                        .append(
                            InstigateTheme.error(
                                "No hardcore statistics found for "
                            )
                        )
                        .append(
                            Component.text(
                                args[1],
                                InstigateTheme.TEXT
                            )
                        )
                        .append(
                            InstigateTheme.error(
                                "."
                            )
                        )
                        .build()
                )
            );

            return;
        }

        PlayerStats stats =
            target.get();

        sendPlayerStats(
            sender,
            stats.uuid(),
            stats.name()
        );
    }

    private Optional<PlayerStats> findPlayerStats(
        String name
    ) {
        return statsManager
            .getPlayersByDeaths()
            .stream()
            .filter(
                stats ->
                    stats.name()
                        .equalsIgnoreCase(
                            name
                        )
            )
            .findFirst();
    }

    private void sendPlayerStats(
        CommandSender sender,
        UUID uuid,
        String name
    ) {
        int deaths =
            statsManager
                .getDeaths(
                    uuid
                );

        int attemptsPlayed =
            participantManager
                .getAttemptsPlayed(
                    uuid
                );

        long totalPlaytimeMillis =
            telemetryManager
                .getTotalPlaytimeMillis(
                    uuid
                );

        long averagePlaytimeMillis =
            attemptsPlayed == 0
                ? 0L
                : totalPlaytimeMillis
                    / attemptsPlayed;

        Optional<String> mostCommonCause =
            telemetryManager
                .getMostCommonDeathCause(
                    uuid
                );

        List<PlayerDeathRecord> latestDeaths =
            telemetryManager
                .getLatestDeaths(
                    uuid,
                    3
                );

        sender.sendMessage(
            Component.empty()
        );

        sender.sendMessage(
            InstigateTheme.divider()
        );

        sender.sendMessage(
            InstigateTheme.brand()
        );

        sender.sendMessage(
            Component.text()
                .append(
                    Component.text(
                        name,
                        InstigateTheme.TEXT
                    ).decorate(
                        TextDecoration.BOLD
                    )
                )
                .append(
                    InstigateTheme.muted(
                        "  /  Player Stats"
                    )
                )
                .build()
        );

        sender.sendMessage(
            Component.empty()
        );

        sendStatusEntry(
            sender,
            "Deaths",
            Integer.toString(
                deaths
            ),
            deaths > 0
                ? InstigateTheme.PURPLE
                : InstigateTheme.TEXT
        );

        sendStatusEntry(
            sender,
            "Attempts Played",
            Integer.toString(
                attemptsPlayed
            ),
            InstigateTheme.TEXT
        );

        sendStatusEntry(
            sender,
            "Total Playtime",
            formatPlaytime(
                totalPlaytimeMillis
            ),
            InstigateTheme.TEXT
        );

        sendStatusEntry(
            sender,
            "Average Attempt",
            formatPlaytime(
                averagePlaytimeMillis
            ),
            InstigateTheme.TEXT
        );

        if (
            mostCommonCause.isPresent()
        ) {
            String cause =
                mostCommonCause.get();

            long count =
                telemetryManager
                    .getDeathCauseCount(
                        uuid,
                        cause
                    );

            sendStatusEntry(
                sender,
                "Most Killed By",
                cause
                    + " ("
                    + count
                    + ")",
                InstigateTheme.PURPLE
            );
        } else {
            sendStatusEntry(
                sender,
                "Most Killed By",
                "None",
                InstigateTheme.MUTED
            );
        }

        sender.sendMessage(
            Component.empty()
        );

        sender.sendMessage(
            InstigateTheme.subheading(
                "Latest Deaths"
            )
        );

        if (latestDeaths.isEmpty()) {
            sender.sendMessage(
                InstigateTheme.muted(
                    "No recorded deaths."
                )
            );
        } else {
            for (
                PlayerDeathRecord death :
                latestDeaths
            ) {
                sender.sendMessage(
                    Component.text()
                        .append(
                            Component.text(
                                "#"
                                    + death.attempt(),
                                InstigateTheme.PURPLE
                            )
                        )
                        .append(
                            InstigateTheme.muted(
                                "  "
                            )
                        )
                        .append(
                            Component.text(
                                death.cause(),
                                InstigateTheme.TEXT
                            )
                        )
                        .append(
                            InstigateTheme.muted(
                                "  ·  "
                            )
                        )
                        .append(
                            InstigateTheme.secondary(
                                formatRelativeTime(
                                    death.timestamp()
                                )
                            )
                        )
                        .build()
                );

                sender.sendMessage(
                    InstigateTheme.muted(
                        "   "
                            + death.message()
                    )
                );
            }
        }

        sender.sendMessage(
            InstigateTheme.divider()
        );

        sender.sendMessage(
            Component.empty()
        );
    }

    /*
     * ------------------------------------------------------------
     * DEATH LEADERBOARD
     * ------------------------------------------------------------
     */

    private void sendDeaths(
        CommandSender sender
    ) {
        List<PlayerStats> leaderboard =
            statsManager
                .getPlayersByDeaths();

        sender.sendMessage(
            Component.empty()
        );

        sender.sendMessage(
            InstigateTheme.divider()
        );

        sender.sendMessage(
            InstigateTheme.brand()
        );

        sender.sendMessage(
            InstigateTheme.subheading(
                "Death Leaderboard"
            )
        );

        sender.sendMessage(
            Component.empty()
        );

        if (leaderboard.isEmpty()) {
            sender.sendMessage(
                InstigateTheme.muted(
                    "No players have been recorded yet."
                )
            );
        } else {
            int rank =
                1;

            for (
                PlayerStats player :
                leaderboard
            ) {
                sendDeathLeaderboardEntry(
                    sender,
                    rank,
                    player
                );

                rank++;
            }
        }

        sender.sendMessage(
            Component.empty()
        );

        sender.sendMessage(
            Component.text()
                .append(
                    InstigateTheme.secondary(
                        "Current Attempt  "
                    )
                )
                .append(
                    InstigateTheme.attempt(
                        statsManager
                            .getCurrentAttempt()
                    )
                )
                .build()
        );

        sender.sendMessage(
            InstigateTheme.divider()
        );

        sender.sendMessage(
            Component.empty()
        );
    }

    private void sendDeathLeaderboardEntry(
        CommandSender sender,
        int rank,
        PlayerStats player
    ) {
        int deaths =
            player.deaths();

        sender.sendMessage(
            Component.text()
                .append(
                    Component.text(
                        rank
                            + ". ",
                        InstigateTheme.MUTED
                    )
                )
                .append(
                    clickablePlayerName(
                        player.name(),
                        InstigateTheme.TEXT
                    )
                )
                .append(
                    InstigateTheme.muted(
                        "  ·  "
                    )
                )
                .append(
                    Component.text(
                        deaths
                            + " death"
                            + (
                                deaths == 1
                                    ? ""
                                    : "s"
                            ),
                        deaths > 0
                            ? InstigateTheme.PURPLE
                            : InstigateTheme.MUTED
                    )
                )
                .build()
        );

        if (deaths <= 0) {
            return;
        }

        Optional<String> mostCommon =
            telemetryManager
                .getMostCommonDeathCause(
                    player.uuid()
                );

        List<PlayerDeathRecord> latest =
            telemetryManager
                .getLatestDeaths(
                    player.uuid(),
                    1
                );

        Component details =
            Component.empty();

        boolean hasDetails =
            false;

        if (
            mostCommon.isPresent()
        ) {
            String cause =
                mostCommon.get();

            long count =
                telemetryManager
                    .getDeathCauseCount(
                        player.uuid(),
                        cause
                    );

            details =
                details.append(
                    InstigateTheme.muted(
                        "   Most  "
                    )
                );

            details =
                details.append(
                    InstigateTheme.secondary(
                        cause
                            + " ("
                            + count
                            + ")"
                    )
                );

            hasDetails =
                true;
        }

        if (!latest.isEmpty()) {
            if (hasDetails) {
                details =
                    details.append(
                        InstigateTheme.muted(
                            "  ·  "
                        )
                    );
            } else {
                details =
                    details.append(
                        InstigateTheme.muted(
                            "   "
                        )
                    );
            }

            details =
                details.append(
                    InstigateTheme.muted(
                        "Latest  "
                    )
                );

            details =
                details.append(
                    Component.text(
                        "Attempt #"
                            + latest
                                .getFirst()
                                .attempt(),
                        InstigateTheme.SECONDARY
                    )
                );

            hasDetails =
                true;
        }

        if (hasDetails) {
            sender.sendMessage(
                details
            );
        }
    }

    /*
     * ------------------------------------------------------------
     * STATUS
     * ------------------------------------------------------------
     */

    private void sendStatus(
        CommandSender sender
    ) {
        int attempt =
            statsManager
                .getCurrentAttempt();

        List<AttemptParticipant> participants =
            participantManager
                .getParticipants(
                    attempt
                );

        long activeOnline =
            participants.stream()
                .filter(
                    this::isActiveOnline
                )
                .count();

        WorldSet active =
            worldSetManager
                .getActiveWorldSet();

        WorldSet standby =
            worldSetManager
                .getStandbyWorldSet();

        sender.sendMessage(
            Component.empty()
        );

        sender.sendMessage(
            InstigateTheme.divider()
        );

        sender.sendMessage(
            InstigateTheme.brand()
        );

        sender.sendMessage(
            InstigateTheme.subheading(
                "Server Status"
            )
        );

        sender.sendMessage(
            Component.empty()
        );

        sendStatusEntry(
            sender,
            "Attempt",
            "#"
                + attempt,
            InstigateTheme.PURPLE
        );

        sendStatusEntry(
            sender,
            "State",
            formatEnumLabel(
                runManager
                    .getState()
                    .name()
            ),
            runManager.isActive()
                ? InstigateTheme.AZURE
                : InstigateTheme.PURPLE
        );

        sendStatusEntry(
            sender,
            "Run Time",
            formatDuration(
                runManager
                    .getElapsedTime()
            ),
            InstigateTheme.TEXT
        );

        sender.sendMessage(
            Component.empty()
        );

        sender.sendMessage(
            Component.text()
                .append(
                    InstigateTheme.secondary(
                        "Players  "
                    )
                )
                .append(
                    Component.text(
                        activeOnline
                            + " / "
                            + participants.size(),
                        InstigateTheme.TEXT
                    )
                )
                .build()
        );

        if (participants.isEmpty()) {
            sender.sendMessage(
                InstigateTheme.muted(
                    "No participants have entered this attempt yet."
                )
            );
        } else {
            for (
                AttemptParticipant participant :
                participants
            ) {
                sendParticipant(
                    sender,
                    participant
                );
            }
        }

        sender.sendMessage(
            Component.empty()
        );

        if (active != null) {
            sendStatusEntry(
                sender,
                "Active",
                "Attempt #"
                    + active
                        .attemptNumber(),
                InstigateTheme.AZURE
            );
        }

        if (standby != null) {
            sendStatusEntry(
                sender,
                "Standby",
                "Attempt #"
                    + standby
                        .attemptNumber(),
                InstigateTheme.PURPLE
            );
        } else {
            sendStatusEntry(
                sender,
                "Standby",
                "Preparing",
                InstigateTheme.SECONDARY
            );
        }

        sender.sendMessage(
            Component.empty()
        );

        sendStatusEntry(
            sender,
            "Rotation",
            worldRotationManager
                .isRotationInProgress()
                ? "Running"
                : "Idle",
            worldRotationManager
                .isRotationInProgress()
                ? InstigateTheme.PURPLE
                : InstigateTheme.MUTED
        );

        sendStatusEntry(
            sender,
            "Cleanup",
            worldCleanupManager
                .isCleanupInProgress()
                ? "Running"
                : "Idle",
            worldCleanupManager
                .isCleanupInProgress()
                ? InstigateTheme.PURPLE
                : InstigateTheme.MUTED
        );

        sender.sendMessage(
            InstigateTheme.divider()
        );

        sender.sendMessage(
            Component.empty()
        );
    }

    private void sendParticipant(
        CommandSender sender,
        AttemptParticipant participant
    ) {
        Player player =
            Bukkit.getPlayer(
                participant.uuid()
            );

        boolean online =
            player != null
                && player.isOnline();

        boolean active =
            online
                && worldSetManager
                    .isActiveWorld(
                        player.getWorld()
                    );

        TextColor nameColor =
            active
                ? InstigateTheme.TEXT
                : online
                    ? InstigateTheme.SECONDARY
                    : InstigateTheme.MUTED;

        String state;
        TextColor stateColor;

        if (active) {
            state =
                "Online";

            stateColor =
                InstigateTheme.AZURE;
        } else if (online) {
            state =
                "Outside Run";

            stateColor =
                InstigateTheme.SECONDARY;
        } else {
            state =
                "Offline";

            stateColor =
                InstigateTheme.MUTED;
        }

        sender.sendMessage(
            Component.text()
                .append(
                    clickablePlayerName(
                        participant.name(),
                        nameColor
                    )
                )
                .append(
                    InstigateTheme.muted(
                        "  ·  "
                    )
                )
                .append(
                    Component.text(
                        state,
                        stateColor
                    )
                )
                .build()
        );
    }

    private boolean isActiveOnline(
        AttemptParticipant participant
    ) {
        Player player =
            Bukkit.getPlayer(
                participant.uuid()
            );

        return player != null
            && player.isOnline()
            && worldSetManager
                .isActiveWorld(
                    player.getWorld()
                );
    }

    /*
     * ------------------------------------------------------------
     * WORLD DIAGNOSTICS
     * ------------------------------------------------------------
     */

    private void sendWorlds(
        CommandSender sender
    ) {
        WorldSet active =
            worldSetManager
                .getActiveWorldSet();

        WorldSet standby =
            worldSetManager
                .getStandbyWorldSet();

        WorldSet retired =
            worldSetManager
                .getRetiredWorldSet();

        sender.sendMessage(
            Component.empty()
        );

        sender.sendMessage(
            InstigateTheme.divider()
        );

        sender.sendMessage(
            InstigateTheme.brand()
        );

        sender.sendMessage(
            InstigateTheme.subheading(
                "World Diagnostics"
            )
        );

        sender.sendMessage(
            Component.empty()
        );

        sendWorldSetSection(
            sender,
            "Active",
            active,
            InstigateTheme.AZURE
        );

        sender.sendMessage(
            Component.empty()
        );

        sendWorldSetSection(
            sender,
            "Standby",
            standby,
            InstigateTheme.PURPLE
        );

        sender.sendMessage(
            Component.empty()
        );

        sendWorldSetSection(
            sender,
            "Retired",
            retired,
            InstigateTheme.MUTED
        );

        sender.sendMessage(
            Component.empty()
        );

        try {
            WorldRotationState state =
                worldStateStore
                    .load();

            sendStatusEntry(
                sender,
                "Persistent Phase",
                formatEnumLabel(
                    state
                        .phase()
                        .name()
                ),
                state.phase()
                    == WorldRotationPhase.STABLE
                        ? InstigateTheme.AZURE
                        : InstigateTheme.PURPLE
            );
        } catch (
            IOException exception
        ) {
            sendStatusEntry(
                sender,
                "Persistent Phase",
                "Error",
                InstigateTheme.ERROR
            );
        }

        sender.sendMessage(
            InstigateTheme.divider()
        );

        sender.sendMessage(
            Component.empty()
        );
    }

    private void sendWorldSetSection(
        CommandSender sender,
        String title,
        WorldSet worldSet,
        TextColor titleColor
    ) {
        sender.sendMessage(
            Component.text(
                title,
                titleColor
            ).decorate(
                TextDecoration.BOLD
            )
        );

        if (worldSet == null) {
            sender.sendMessage(
                InstigateTheme.muted(
                    "None"
                )
            );

            return;
        }

        sendStatusEntry(
            sender,
            "Attempt",
            "#"
                + worldSet
                    .attemptNumber(),
            InstigateTheme.PURPLE
        );

        sendStatusEntry(
            sender,
            "Seed",
            Long.toString(
                worldSet
                    .seed()
            ),
            InstigateTheme.SECONDARY
        );

        sendStatusEntry(
            sender,
            "Overworld",
            worldSet
                .overworld()
                .getName(),
            InstigateTheme.TEXT
        );

        sendStatusEntry(
            sender,
            "Nether",
            worldSet
                .nether()
                .getName(),
            InstigateTheme.TEXT
        );

        sendStatusEntry(
            sender,
            "End",
            worldSet
                .end()
                .getName(),
            InstigateTheme.TEXT
        );
    }

    /*
     * ------------------------------------------------------------
     * DEBUG
     * ------------------------------------------------------------
     */

    private void sendDebug(
        CommandSender sender
    ) {
        int statsAttempt =
            statsManager
                .getCurrentAttempt();

        WorldSet active =
            worldSetManager
                .getActiveWorldSet();

        WorldSet standby =
            worldSetManager
                .getStandbyWorldSet();

        WorldSet retired =
            worldSetManager
                .getRetiredWorldSet();

        List<String> warnings =
            new ArrayList<>();

        WorldRotationState persistentState =
            null;

        try {
            persistentState =
                worldStateStore
                    .load();
        } catch (
            IOException exception
        ) {
            warnings.add(
                "Unable to read persistent world state."
            );
        }

        if (active == null) {
            warnings.add(
                "ACTIVE WorldSet is missing."
            );
        } else if (
            active.attemptNumber()
                != statsAttempt
        ) {
            warnings.add(
                "Stats attempt #"
                    + statsAttempt
                    + " != ACTIVE #"
                    + active.attemptNumber()
                    + "."
            );
        }

        if (
            active != null
                && standby != null
                && standby.attemptNumber()
                    != active.attemptNumber()
                        + 1
        ) {
            warnings.add(
                "STANDBY #"
                    + standby.attemptNumber()
                    + " should be #"
                    + (
                        active.attemptNumber()
                            + 1
                    )
                    + "."
            );
        }

        if (
            runManager.isActive()
                && standby == null
        ) {
            warnings.add(
                "No STANDBY WorldSet while run is ACTIVE."
            );
        }

        if (
            persistentState != null
                && active != null
                && persistentState
                    .activeAttempt()
                    != active
                        .attemptNumber()
        ) {
            warnings.add(
                "Persistent ACTIVE #"
                    + persistentState
                        .activeAttempt()
                    + " != loaded ACTIVE #"
                    + active
                        .attemptNumber()
                    + "."
            );
        }

        if (
            persistentState != null
                && standby != null
                && persistentState
                    .standbyAttempt()
                    != standby
                        .attemptNumber()
        ) {
            warnings.add(
                "Persistent STANDBY #"
                    + persistentState
                        .standbyAttempt()
                    + " != loaded STANDBY #"
                    + standby
                        .attemptNumber()
                    + "."
            );
        }

        if (
            persistentState != null
                && runManager.isActive()
                && persistentState.phase()
                    != WorldRotationPhase.STABLE
        ) {
            warnings.add(
                "Persistent phase is "
                    + persistentState
                        .phase()
                        .name()
                    + " while run is ACTIVE."
            );
        }

        int onlinePlayers =
            Bukkit
                .getOnlinePlayers()
                .size();

        int activePlayers =
            (int) Bukkit
                .getOnlinePlayers()
                .stream()
                .filter(
                    player ->
                        worldSetManager
                            .isActiveWorld(
                                player.getWorld()
                            )
                )
                .count();

        int participants =
            participantManager
                .getParticipantCount(
                    statsAttempt
                );

        sender.sendMessage(
            Component.empty()
        );

        sender.sendMessage(
            InstigateTheme.divider()
        );

        sender.sendMessage(
            InstigateTheme.brand()
        );

        sender.sendMessage(
            InstigateTheme.subheading(
                "Debug Status"
            )
        );

        sender.sendMessage(
            Component.empty()
        );

        sendStatusEntry(
            sender,
            "Run State",
            formatEnumLabel(
                runManager
                    .getState()
                    .name()
            ),
            runManager.isActive()
                ? InstigateTheme.AZURE
                : InstigateTheme.PURPLE
        );

        sendStatusEntry(
            sender,
            "Persistent Phase",
            persistentState == null
                ? "Error"
                : formatEnumLabel(
                    persistentState
                        .phase()
                        .name()
                ),
            persistentState == null
                ? InstigateTheme.ERROR
                : persistentState.phase()
                    == WorldRotationPhase.STABLE
                        ? InstigateTheme.AZURE
                        : InstigateTheme.PURPLE
        );

        sender.sendMessage(
            Component.empty()
        );

        sendStatusEntry(
            sender,
            "Stats Attempt",
            "#"
                + statsAttempt,
            InstigateTheme.TEXT
        );

        sendStatusEntry(
            sender,
            "Active Attempt",
            active == null
                ? "None"
                : "#"
                    + active
                        .attemptNumber(),
            active == null
                ? InstigateTheme.ERROR
                : InstigateTheme.AZURE
        );

        sendStatusEntry(
            sender,
            "Standby Attempt",
            standby == null
                ? "None"
                : "#"
                    + standby
                        .attemptNumber(),
            standby == null
                ? InstigateTheme.SECONDARY
                : InstigateTheme.PURPLE
        );

        sendStatusEntry(
            sender,
            "Retired Attempt",
            retired == null
                ? "None"
                : "#"
                    + retired
                        .attemptNumber(),
            InstigateTheme.MUTED
        );

        sender.sendMessage(
            Component.empty()
        );

        sendStatusEntry(
            sender,
            "Countdown",
            countdownManager
                .isRunning()
                ? "Running"
                : "Idle",
            countdownManager
                .isRunning()
                ? InstigateTheme.PURPLE
                : InstigateTheme.MUTED
        );

        sendStatusEntry(
            sender,
            "Rotation",
            worldRotationManager
                .isRotationInProgress()
                ? "Running"
                : "Idle",
            worldRotationManager
                .isRotationInProgress()
                ? InstigateTheme.PURPLE
                : InstigateTheme.MUTED
        );

        sendStatusEntry(
            sender,
            "Cleanup",
            worldCleanupManager
                .isCleanupInProgress()
                ? "Running"
                : "Idle",
            worldCleanupManager
                .isCleanupInProgress()
                ? InstigateTheme.PURPLE
                : InstigateTheme.MUTED
        );

        sender.sendMessage(
            Component.empty()
        );

        sendStatusEntry(
            sender,
            "Players Online",
            Integer.toString(
                onlinePlayers
            ),
            InstigateTheme.TEXT
        );

        sendStatusEntry(
            sender,
            "Players In Active",
            Integer.toString(
                activePlayers
            ),
            InstigateTheme.TEXT
        );

        sendStatusEntry(
            sender,
            "Attempt Participants",
            Integer.toString(
                participants
            ),
            InstigateTheme.TEXT
        );

        sender.sendMessage(
            Component.empty()
        );

        if (warnings.isEmpty()) {
            sendStatusEntry(
                sender,
                "Integrity",
                "OK",
                InstigateTheme.AZURE
            );
        } else {
            sendStatusEntry(
                sender,
                "Integrity",
                "Warning",
                InstigateTheme.ERROR
            );

            for (
                String warning :
                warnings
            ) {
                sender.sendMessage(
                    Component.text()
                        .append(
                            Component.text(
                                "• ",
                                InstigateTheme.ERROR
                            )
                        )
                        .append(
                            Component.text(
                                warning,
                                InstigateTheme.ERROR
                            )
                        )
                        .build()
                );
            }
        }

        sender.sendMessage(
            InstigateTheme.divider()
        );

        sender.sendMessage(
            Component.empty()
        );
    }

    /*
     * ------------------------------------------------------------
     * HELP
     * ------------------------------------------------------------
     */

    private void sendHelp(
        CommandSender sender
    ) {
        sender.sendMessage(
            Component.empty()
        );

        sender.sendMessage(
            InstigateTheme.divider()
        );

        sender.sendMessage(
            InstigateTheme.brand()
        );

        sender.sendMessage(
            InstigateTheme.subheading(
                "Commands"
            )
        );

        sender.sendMessage(
            Component.empty()
        );

        sendHelpEntry(
            sender,
            "/hc help",
            "Show this command page."
        );

        sendHelpEntry(
            sender,
            "/hc status",
            "Show the current attempt and participants."
        );

        sendHelpEntry(
            sender,
            "/hc stats",
            "Show your hardcore statistics."
        );

        sendHelpEntry(
            sender,
            "/hc stats <player>",
            "Show another player's statistics."
        );

        sendHelpEntry(
            sender,
            "/hc deaths",
            "Show the death leaderboard."
        );

        if (
            sender.hasPermission(
                "instigatehardcore.admin"
            )
        ) {
            sender.sendMessage(
                Component.empty()
            );

            sender.sendMessage(
                InstigateTheme.subheading(
                    "Admin"
                )
            );

            sendHelpEntry(
                sender,
                "/hc reset",
                "End the current attempt with confirmation."
            );

            sendHelpEntry(
                sender,
                "/hc worlds",
                "Inspect the world rotation pipeline."
            );
        }

        if (
            sender.hasPermission(
                "instigatehardcore.debug"
            )
        ) {
            sendHelpEntry(
                sender,
                "/hc debug",
                "Show internal state and integrity checks."
            );
        }

        sender.sendMessage(
            Component.empty()
        );

        sender.sendMessage(
            InstigateTheme.muted(
                "Aliases  /hardcore  ·  /hc"
            )
        );

        sender.sendMessage(
            InstigateTheme.divider()
        );

        sender.sendMessage(
            Component.empty()
        );
    }

    /*
     * ------------------------------------------------------------
     * INTERACTIVE COMPONENTS
     * ------------------------------------------------------------
     */

    private Component clickablePlayerName(
        String name,
        TextColor color
    ) {
        return Component.text(
            name,
            color
        )
            .hoverEvent(
                HoverEvent.showText(
                    InstigateTheme.secondary(
                        "View "
                            + name
                            + "'s hardcore stats"
                    )
                )
            )
            .clickEvent(
                ClickEvent.runCommand(
                    "/hc stats "
                        + name
                )
            );
    }

    /*
     * ------------------------------------------------------------
     * COMMON FORMATTERS
     * ------------------------------------------------------------
     */

    private String formatPlaytime(
        long milliseconds
    ) {
        long totalSeconds =
            Math.max(
                0L,
                milliseconds / 1000L
            );

        long hours =
            totalSeconds / 3600L;

        long minutes =
            (
                totalSeconds % 3600L
            ) / 60L;

        long seconds =
            totalSeconds % 60L;

        if (hours > 0) {
            return String.format(
                "%dh %02dm %02ds",
                hours,
                minutes,
                seconds
            );
        }

        if (minutes > 0) {
            return String.format(
                "%dm %02ds",
                minutes,
                seconds
            );
        }

        return seconds
            + "s";
    }

    private String formatRelativeTime(
        long timestamp
    ) {
        long seconds =
            Math.max(
                0L,
                Duration.between(
                    Instant.ofEpochMilli(
                        timestamp
                    ),
                    Instant.now()
                ).getSeconds()
            );

        if (seconds < 60) {
            return seconds
                + "s ago";
        }

        long minutes =
            seconds / 60L;

        if (minutes < 60) {
            return minutes
                + "m ago";
        }

        long hours =
            minutes / 60L;

        if (hours < 24) {
            return hours
                + "h ago";
        }

        long days =
            hours / 24L;

        return days
            + "d ago";
    }

    private String formatDuration(
        Duration duration
    ) {
        long totalSeconds =
            Math.max(
                0L,
                duration.getSeconds()
            );

        long hours =
            totalSeconds / 3600L;

        long minutes =
            (
                totalSeconds % 3600L
            ) / 60L;

        long seconds =
            totalSeconds % 60L;

        return String.format(
            "%02d:%02d:%02d",
            hours,
            minutes,
            seconds
        );
    }

    private String formatEnumLabel(
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
            if (word.isBlank()) {
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

        return result.toString();
    }

    private void sendStatusEntry(
        CommandSender sender,
        String label,
        String value,
        TextColor valueColor
    ) {
        sender.sendMessage(
            Component.text()
                .append(
                    Component.text(
                        label
                            + "  ",
                        InstigateTheme.SECONDARY
                    )
                )
                .append(
                    Component.text(
                        value,
                        valueColor
                    )
                )
                .build()
        );
    }

    private void sendHelpEntry(
        CommandSender sender,
        String syntax,
        String description
    ) {
        String suggestedCommand =
            commandSuggestionFromSyntax(
                syntax
            );

        Component commandComponent =
            Component.text(
                syntax,
                InstigateTheme.PURPLE
            )
                .hoverEvent(
                    HoverEvent.showText(
                        InstigateTheme.secondary(
                            "Click to use this command."
                        )
                    )
                )
                .clickEvent(
                    ClickEvent.suggestCommand(
                        suggestedCommand
                    )
                );

        sender.sendMessage(
            Component.text()
                .append(
                    commandComponent
                )
                .append(
                    InstigateTheme.muted(
                        "  ·  "
                    )
                )
                .append(
                    InstigateTheme.secondary(
                        description
                    )
                )
                .build()
        );
    }

    private String commandSuggestionFromSyntax(
        String syntax
    ) {
        int placeholderStart =
            syntax.indexOf(
                '<'
            );

        if (placeholderStart >= 0) {
            return syntax
                .substring(
                    0,
                    placeholderStart
                )
                .stripTrailing();
        }

        int optionalStart =
            syntax.indexOf(
                '['
            );

        if (optionalStart >= 0) {
            return syntax
                .substring(
                    0,
                    optionalStart
                )
                .stripTrailing();
        }

        return syntax;
    }

    /*
     * ------------------------------------------------------------
     * DIRECT FEEDBACK
     * ------------------------------------------------------------
     */

    private void sendUnknownCommand(
        CommandSender sender,
        String subcommand
    ) {
        sender.sendMessage(
            InstigateTheme.chat(
                Component.text()
                    .append(
                        InstigateTheme.error(
                            "Unknown command: "
                        )
                    )
                    .append(
                        Component.text(
                            subcommand,
                            InstigateTheme.TEXT
                        )
                    )
                    .append(
                        InstigateTheme.error(
                            "."
                        )
                    )
                    .build()
            )
        );

        sender.sendMessage(
            InstigateTheme.chat(
                InstigateTheme.secondary(
                    "Use /hc help to view available commands."
                )
            )
        );
    }

    private void sendNoPermission(
        CommandSender sender
    ) {
        sender.sendMessage(
            InstigateTheme.chat(
                InstigateTheme.error(
                    "You do not have permission to use that command."
                )
            )
        );
    }

    private record ResetConfirmation(
        int attempt,
        long expiresAt
    ) {
    }
}