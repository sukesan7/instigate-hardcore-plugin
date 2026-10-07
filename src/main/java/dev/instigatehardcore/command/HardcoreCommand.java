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

import net.kyori.adventure.text.format.NamedTextColor;
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
            Component.text(
                "Usage: /hc reset [confirm]",
                NamedTextColor.RED
            )
        );
    }

    private void requestResetConfirmation(
        CommandSender sender
    ) {
        if (!runManager.isActive()) {
            sender.sendMessage(
                Component.text()
                    .append(
                        Component.text(
                            "The current attempt cannot be reset while state is ",
                            NamedTextColor.RED
                        )
                    )
                    .append(
                        Component.text(
                            runManager
                                .getState()
                                .name(),
                            NamedTextColor.WHITE
                        )
                    )
                    .append(
                        Component.text(
                            ".",
                            NamedTextColor.RED
                        )
                    )
                    .build()
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
            divider()
        );

        sender.sendMessage(
            brand()
        );

        sender.sendMessage(
            Component.text(
                "ADMINISTRATIVE RESET",
                NamedTextColor.RED
            ).decorate(
                TextDecoration.BOLD
            )
        );

        sender.sendMessage(
            Component.empty()
        );

        sender.sendMessage(
            Component.text(
                "This will permanently end Attempt #"
                    + attempt
                    + ".",
                NamedTextColor.WHITE
            )
        );

        sender.sendMessage(
            Component.text(
                "No player death will be recorded.",
                NamedTextColor.GRAY
            )
        );

        sender.sendMessage(
            Component.empty()
        );

        Component confirmationCommand =
            Component.text(
                "/hc reset confirm",
                NamedTextColor.GOLD
            )
                .decorate(
                    TextDecoration.BOLD
                )
                .hoverEvent(
                    HoverEvent.showText(
                        Component.text(
                            "Click to place the confirmation command in chat.",
                            NamedTextColor.GRAY
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
                    Component.text(
                        "Run ",
                        NamedTextColor.GRAY
                    )
                )
                .append(
                    confirmationCommand
                )
                .append(
                    Component.text(
                        " within 30 seconds to continue.",
                        NamedTextColor.GRAY
                    )
                )
                .build()
        );

        sender.sendMessage(
            divider()
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
                Component.text(
                    "No active reset confirmation. Run /hc reset first.",
                    NamedTextColor.RED
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
                Component.text(
                    "Reset confirmation expired. Run /hc reset again.",
                    NamedTextColor.RED
                )
            );

            return;
        }

        if (!runManager.isActive()) {
            sender.sendMessage(
                Component.text(
                    "The attempt is no longer ACTIVE.",
                    NamedTextColor.RED
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
                Component.text(
                    "That confirmation was for Attempt #"
                        + confirmation.attempt()
                        + ", but the server is now on Attempt #"
                        + currentAttempt
                        + ". Run /hc reset again.",
                    NamedTextColor.RED
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
                Component.text(
                    "The attempt could not be reset because it is no longer ACTIVE.",
                    NamedTextColor.RED
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
                    Component.text(
                        "Console must specify a player: /hc stats <player>",
                        NamedTextColor.RED
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
                Component.text(
                    "Usage: /hc stats [player]",
                    NamedTextColor.RED
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
                Component.text()
                    .append(
                        Component.text(
                            "No hardcore statistics found for ",
                            NamedTextColor.RED
                        )
                    )
                    .append(
                        Component.text(
                            args[1],
                            NamedTextColor.WHITE
                        )
                    )
                    .append(
                        Component.text(
                            ".",
                            NamedTextColor.RED
                        )
                    )
                    .build()
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
            divider()
        );

        sender.sendMessage(
            brand()
        );

        Component statsHeading =
            Component.text(
                name,
                NamedTextColor.RED
            )
                .decorate(
                    TextDecoration.BOLD
                )
                .hoverEvent(
                    HoverEvent.showText(
                        Component.text(
                            "Hardcore statistics for "
                                + name,
                            NamedTextColor.GRAY
                        )
                    )
                )
                .append(
                    Component.text(
                        " — PLAYER STATS",
                        NamedTextColor.RED
                    )
                );

        sender.sendMessage(
            statsHeading
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
                ? NamedTextColor.RED
                : NamedTextColor.GREEN
        );

        sendStatusEntry(
            sender,
            "Attempts Played",
            Integer.toString(
                attemptsPlayed
            ),
            NamedTextColor.WHITE
        );

        sendStatusEntry(
            sender,
            "Total Playtime",
            formatPlaytime(
                totalPlaytimeMillis
            ),
            NamedTextColor.WHITE
        );

        sendStatusEntry(
            sender,
            "Average Attempt",
            formatPlaytime(
                averagePlaytimeMillis
            ),
            NamedTextColor.WHITE
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
                NamedTextColor.RED
            );
        } else {
            sendStatusEntry(
                sender,
                "Most Killed By",
                "None",
                NamedTextColor.GRAY
            );
        }

        sender.sendMessage(
            Component.empty()
        );

        sender.sendMessage(
            Component.text(
                "LATEST DEATHS",
                NamedTextColor.GOLD
            ).decorate(
                TextDecoration.BOLD
            )
        );

        if (latestDeaths.isEmpty()) {
            sender.sendMessage(
                Component.text(
                    "No recorded deaths.",
                    NamedTextColor.DARK_GRAY
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
                                NamedTextColor.GOLD
                            )
                        )
                        .append(
                            Component.text(
                                "  ",
                                NamedTextColor.DARK_GRAY
                            )
                        )
                        .append(
                            Component.text(
                                death.cause(),
                                NamedTextColor.RED
                            )
                        )
                        .append(
                            Component.text(
                                "  •  ",
                                NamedTextColor.DARK_GRAY
                            )
                        )
                        .append(
                            Component.text(
                                formatRelativeTime(
                                    death.timestamp()
                                ),
                                NamedTextColor.GRAY
                            )
                        )
                        .build()
                );

                sender.sendMessage(
                    Component.text(
                        "   "
                            + death.message(),
                        NamedTextColor.DARK_GRAY
                    )
                );
            }
        }

        sender.sendMessage(
            divider()
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
            divider()
        );

        sender.sendMessage(
            brand()
        );

        sender.sendMessage(
            Component.text(
                "DEATH LEADERBOARD",
                NamedTextColor.RED
            )
        );

        sender.sendMessage(
            Component.empty()
        );

        if (leaderboard.isEmpty()) {
            sender.sendMessage(
                Component.text(
                    "No players have been recorded yet.",
                    NamedTextColor.DARK_GRAY
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
            Component.text(
                "Current Attempt: #"
                    + statsManager
                        .getCurrentAttempt(),
                NamedTextColor.GRAY
            )
        );

        sender.sendMessage(
            divider()
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
                        NamedTextColor.GOLD
                    )
                )
                .append(
                    clickablePlayerName(
                        player.name(),
                        NamedTextColor.WHITE
                    )
                )
                .append(
                    Component.text(
                        "  •  ",
                        NamedTextColor.DARK_GRAY
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
                            ? NamedTextColor.RED
                            : NamedTextColor.GRAY
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
            Component.text(
                "   ",
                NamedTextColor.DARK_GRAY
            );

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
                    Component.text(
                        "Most: ",
                        NamedTextColor.DARK_GRAY
                    )
                );

            details =
                details.append(
                    Component.text(
                        cause
                            + " ("
                            + count
                            + ")",
                        NamedTextColor.GRAY
                    )
                );

            hasDetails =
                true;
        }

        if (!latest.isEmpty()) {
            if (hasDetails) {
                details =
                    details.append(
                        Component.text(
                            "  •  ",
                            NamedTextColor.DARK_GRAY
                        )
                    );
            }

            details =
                details.append(
                    Component.text(
                        "Latest: ",
                        NamedTextColor.DARK_GRAY
                    )
                );

            details =
                details.append(
                    Component.text(
                        "Attempt #"
                            + latest
                                .getFirst()
                                .attempt(),
                        NamedTextColor.GRAY
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
            divider()
        );

        sender.sendMessage(
            brand()
        );

        sender.sendMessage(
            Component.text(
                "SERVER STATUS",
                NamedTextColor.RED
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
            NamedTextColor.GOLD
        );

        sendStatusEntry(
            sender,
            "State",
            runManager
                .getState()
                .name(),
            runManager.isActive()
                ? NamedTextColor.GREEN
                : NamedTextColor.RED
        );

        sendStatusEntry(
            sender,
            "Run Time",
            formatDuration(
                runManager
                    .getElapsedTime()
            ),
            NamedTextColor.WHITE
        );

        sender.sendMessage(
            Component.empty()
        );

        sender.sendMessage(
            Component.text()
                .append(
                    Component.text(
                        "PLAYERS  ",
                        NamedTextColor.GOLD
                    ).decorate(
                        TextDecoration.BOLD
                    )
                )
                .append(
                    Component.text(
                        activeOnline
                            + " / "
                            + participants.size(),
                        NamedTextColor.WHITE
                    )
                )
                .build()
        );

        if (participants.isEmpty()) {
            sender.sendMessage(
                Component.text(
                    "No participants have entered this attempt yet.",
                    NamedTextColor.DARK_GRAY
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
                "ACTIVE WORLD",
                "Attempt #"
                    + active
                        .attemptNumber(),
                NamedTextColor.GREEN
            );
        }

        if (standby != null) {
            sendStatusEntry(
                sender,
                "STANDBY WORLD",
                "Attempt #"
                    + standby
                        .attemptNumber()
                    + " • Ready",
                NamedTextColor.AQUA
            );
        } else {
            sendStatusEntry(
                sender,
                "STANDBY WORLD",
                "Preparing",
                NamedTextColor.YELLOW
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
                ? "In Progress"
                : "Idle",
            worldRotationManager
                .isRotationInProgress()
                ? NamedTextColor.YELLOW
                : NamedTextColor.GRAY
        );

        sendStatusEntry(
            sender,
            "Cleanup",
            worldCleanupManager
                .isCleanupInProgress()
                ? "In Progress"
                : "Idle",
            worldCleanupManager
                .isCleanupInProgress()
                ? NamedTextColor.YELLOW
                : NamedTextColor.GRAY
        );

        sender.sendMessage(
            divider()
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

        NamedTextColor nameColor =
            active
                ? NamedTextColor.GREEN
                : online
                    ? NamedTextColor.YELLOW
                    : NamedTextColor.GRAY;

        String state;
        NamedTextColor stateColor;

        if (active) {
            state =
                "Online";

            stateColor =
                NamedTextColor.GREEN;
        } else if (online) {
            state =
                "Online • Outside Run";

            stateColor =
                NamedTextColor.YELLOW;
        } else {
            state =
                "Offline";

            stateColor =
                NamedTextColor.DARK_GRAY;
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
                    Component.text(
                        "  •  ",
                        NamedTextColor.DARK_GRAY
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
            divider()
        );

        sender.sendMessage(
            brand()
        );

        sender.sendMessage(
            Component.text(
                "WORLD DIAGNOSTICS",
                NamedTextColor.RED
            )
        );

        sender.sendMessage(
            Component.empty()
        );

        sendWorldSetSection(
            sender,
            "ACTIVE",
            active,
            NamedTextColor.GREEN
        );

        sender.sendMessage(
            Component.empty()
        );

        sendWorldSetSection(
            sender,
            "STANDBY",
            standby,
            NamedTextColor.AQUA
        );

        sender.sendMessage(
            Component.empty()
        );

        sendWorldSetSection(
            sender,
            "RETIRED",
            retired,
            NamedTextColor.GRAY
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
                state
                    .phase()
                    .name(),
                state.phase()
                    == WorldRotationPhase.STABLE
                        ? NamedTextColor.GREEN
                        : NamedTextColor.YELLOW
            );
        } catch (
            IOException exception
        ) {
            sendStatusEntry(
                sender,
                "Persistent Phase",
                "ERROR",
                NamedTextColor.RED
            );
        }

        sender.sendMessage(
            divider()
        );

        sender.sendMessage(
            Component.empty()
        );
    }

    private void sendWorldSetSection(
        CommandSender sender,
        String title,
        WorldSet worldSet,
        NamedTextColor titleColor
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
                Component.text(
                    "None",
                    NamedTextColor.DARK_GRAY
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
            NamedTextColor.WHITE
        );

        sendStatusEntry(
            sender,
            "Seed",
            Long.toString(
                worldSet
                    .seed()
            ),
            NamedTextColor.GRAY
        );

        sendStatusEntry(
            sender,
            "Overworld",
            worldSet
                .overworld()
                .getName(),
            NamedTextColor.WHITE
        );

        sendStatusEntry(
            sender,
            "Nether",
            worldSet
                .nether()
                .getName(),
            NamedTextColor.WHITE
        );

        sendStatusEntry(
            sender,
            "End",
            worldSet
                .end()
                .getName(),
            NamedTextColor.WHITE
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

        /*
         * Stats attempt should always agree with the loaded
         * ACTIVE WorldSet.
         */
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

        /*
         * Standby should always be one attempt ahead.
         */
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

        /*
         * Persistent state should agree with loaded state.
         */
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

        /*
         * ACTIVE gameplay should normally have persistent world
         * state in STABLE.
         */
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
            divider()
        );

        sender.sendMessage(
            brand()
        );

        sender.sendMessage(
            Component.text(
                "DEBUG STATUS",
                NamedTextColor.RED
            )
        );

        sender.sendMessage(
            Component.empty()
        );

        sendStatusEntry(
            sender,
            "Run State",
            runManager
                .getState()
                .name(),
            runManager.isActive()
                ? NamedTextColor.GREEN
                : NamedTextColor.YELLOW
        );

        sendStatusEntry(
            sender,
            "Persistent Phase",
            persistentState == null
                ? "ERROR"
                : persistentState
                    .phase()
                    .name(),
            persistentState == null
                ? NamedTextColor.RED
                : persistentState.phase()
                    == WorldRotationPhase.STABLE
                        ? NamedTextColor.GREEN
                        : NamedTextColor.YELLOW
        );

        sender.sendMessage(
            Component.empty()
        );

        sendStatusEntry(
            sender,
            "Stats Attempt",
            "#"
                + statsAttempt,
            NamedTextColor.WHITE
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
                ? NamedTextColor.RED
                : NamedTextColor.WHITE
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
                ? NamedTextColor.YELLOW
                : NamedTextColor.WHITE
        );

        sendStatusEntry(
            sender,
            "Retired Attempt",
            retired == null
                ? "None"
                : "#"
                    + retired
                        .attemptNumber(),
            retired == null
                ? NamedTextColor.GRAY
                : NamedTextColor.YELLOW
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
                ? NamedTextColor.YELLOW
                : NamedTextColor.GRAY
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
                ? NamedTextColor.YELLOW
                : NamedTextColor.GRAY
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
                ? NamedTextColor.YELLOW
                : NamedTextColor.GRAY
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
            NamedTextColor.WHITE
        );

        sendStatusEntry(
            sender,
            "Players In ACTIVE",
            Integer.toString(
                activePlayers
            ),
            NamedTextColor.WHITE
        );

        sendStatusEntry(
            sender,
            "Attempt Participants",
            Integer.toString(
                participants
            ),
            NamedTextColor.WHITE
        );

        sender.sendMessage(
            Component.empty()
        );

        if (warnings.isEmpty()) {
            sendStatusEntry(
                sender,
                "Integrity",
                "OK",
                NamedTextColor.GREEN
            );
        } else {
            sendStatusEntry(
                sender,
                "Integrity",
                "WARNING",
                NamedTextColor.RED
            );

            for (
                String warning :
                warnings
            ) {
                sender.sendMessage(
                    Component.text(
                        "- "
                            + warning,
                        NamedTextColor.RED
                    )
                );
            }
        }

        sender.sendMessage(
            divider()
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
            divider()
        );

        sender.sendMessage(
            brand()
        );

        sender.sendMessage(
            Component.text(
                "COMMANDS",
                NamedTextColor.RED
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
            "Show your personal hardcore statistics."
        );

        sendHelpEntry(
            sender,
            "/hc stats <player>",
            "Show another player's hardcore statistics."
        );

        sendHelpEntry(
            sender,
            "/hc deaths",
            "Show the server death leaderboard."
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
                Component.text(
                    "ADMIN",
                    NamedTextColor.RED
                ).decorate(
                    TextDecoration.BOLD
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
                "Show active, standby and retired world details."
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
                "Show internal hardcore diagnostics."
            );
        }

        sender.sendMessage(
            Component.empty()
        );

        sender.sendMessage(
            Component.text(
                "Aliases: /hardcore, /hc",
                NamedTextColor.DARK_GRAY
            )
        );

        sender.sendMessage(
            divider()
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
        NamedTextColor color
    ) {
        return Component.text(
            name,
            color
        )
            .hoverEvent(
                HoverEvent.showText(
                    Component.text(
                        "View "
                            + name
                            + "'s hardcore stats",
                        NamedTextColor.GRAY
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

    private void sendStatusEntry(
        CommandSender sender,
        String label,
        String value,
        NamedTextColor valueColor
    ) {
        sender.sendMessage(
            Component.text()
                .append(
                    Component.text(
                        label
                            + "  ",
                        NamedTextColor.GRAY
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
                NamedTextColor.GOLD
            )
                .hoverEvent(
                    HoverEvent.showText(
                        Component.text(
                            "Click to use this command.",
                            NamedTextColor.GRAY
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
                    Component.text(
                        "  •  ",
                        NamedTextColor.DARK_GRAY
                    )
                )
                .append(
                    Component.text(
                        description,
                        NamedTextColor.GRAY
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

    private void sendUnknownCommand(
        CommandSender sender,
        String subcommand
    ) {
        sender.sendMessage(
            Component.text()
                .append(
                    Component.text(
                        "Unknown hardcore command: ",
                        NamedTextColor.RED
                    )
                )
                .append(
                    Component.text(
                        subcommand,
                        NamedTextColor.WHITE
                    )
                )
                .build()
        );

        sender.sendMessage(
            Component.text(
                "Use /hc help to view available commands.",
                NamedTextColor.GRAY
            )
        );
    }

    private void sendNoPermission(
        CommandSender sender
    ) {
        sender.sendMessage(
            Component.text(
                "You do not have permission to use that command.",
                NamedTextColor.RED
            )
        );
    }

    private Component brand() {
        return Component.text(
            "INSTIGATE CAFE HARDCORE",
            NamedTextColor.GOLD
        ).decorate(
            TextDecoration.BOLD
        );
    }

    private Component divider() {
        return Component.text(
            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━",
            NamedTextColor.DARK_GRAY
        );
    }

    private record ResetConfirmation(
        int attempt,
        long expiresAt
    ) {
    }
}