package dev.instigatehardcore.command;

import dev.instigatehardcore.core.RunManager;
import dev.instigatehardcore.participation.AttemptParticipant;
import dev.instigatehardcore.participation.AttemptParticipantManager;
import dev.instigatehardcore.stats.PlayerStats;
import dev.instigatehardcore.stats.StatsManager;
import dev.instigatehardcore.telemetry.PlayerDeathRecord;
import dev.instigatehardcore.telemetry.PlayerTelemetryManager;
import dev.instigatehardcore.world.WorldCleanupManager;
import dev.instigatehardcore.world.WorldRotationManager;
import dev.instigatehardcore.world.WorldSet;
import dev.instigatehardcore.world.WorldSetManager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class HardcoreCommand
    implements CommandExecutor {

    private final RunManager runManager;
    private final StatsManager statsManager;

    private final AttemptParticipantManager participantManager;
    private final PlayerTelemetryManager telemetryManager;

    private final WorldSetManager worldSetManager;
    private final WorldRotationManager worldRotationManager;
    private final WorldCleanupManager worldCleanupManager;

    public HardcoreCommand(
        RunManager runManager,
        StatsManager statsManager,
        AttemptParticipantManager participantManager,
        PlayerTelemetryManager telemetryManager,
        WorldSetManager worldSetManager,
        WorldRotationManager worldRotationManager,
        WorldCleanupManager worldCleanupManager
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

                sendNotImplemented(
                    sender,
                    "reset"
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

                sendNotImplemented(
                    sender,
                    "worlds"
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

                sendNotImplemented(
                    sender,
                    "debug"
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

    private void handleStats(
        CommandSender sender,
        String[] args
    ) {
        if (args.length == 1) {
            if (!(sender instanceof Player player)) {
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
            statsManager.getDeaths(
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
            Component.text(
                "INSTIGATE CAFE HARDCORE",
                NamedTextColor.GOLD
            ).decorate(
                TextDecoration.BOLD
            )
        );

        sender.sendMessage(
            Component.text(
                name + " — PLAYER STATS",
                NamedTextColor.RED
            )
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

        if (mostCommonCause.isPresent()) {
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

                /*
                 * Keep the original vanilla-style message visible
                 * beneath each death.
                 */
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
            Component.text(
                "INSTIGATE CAFE HARDCORE",
                NamedTextColor.GOLD
            ).decorate(
                TextDecoration.BOLD
            )
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
            int rank = 1;

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
                        rank + ". ",
                        NamedTextColor.GOLD
                    )
                )
                .append(
                    Component.text(
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

        if (mostCommon.isPresent()) {
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
            Component.text(
                "INSTIGATE CAFE HARDCORE",
                NamedTextColor.GOLD
            ).decorate(
                TextDecoration.BOLD
            )
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
            "#" + attempt,
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
                    + active.attemptNumber(),
                NamedTextColor.GREEN
            );
        }

        if (standby != null) {
            sendStatusEntry(
                sender,
                "STANDBY WORLD",
                "Attempt #"
                    + standby.attemptNumber()
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
                    Component.text(
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

        return seconds + "s";
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
            return seconds + "s ago";
        }

        long minutes =
            seconds / 60;

        if (minutes < 60) {
            return minutes + "m ago";
        }

        long hours =
            minutes / 60;

        if (hours < 24) {
            return hours + "h ago";
        }

        long days =
            hours / 24;

        return days + "d ago";
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
            totalSeconds / 3600;

        long minutes =
            (
                totalSeconds % 3600
            ) / 60;

        long seconds =
            totalSeconds % 60;

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
                        label + "  ",
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
            Component.text(
                "INSTIGATE CAFE HARDCORE",
                NamedTextColor.GOLD
            ).decorate(
                TextDecoration.BOLD
            )
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

    private void sendHelpEntry(
        CommandSender sender,
        String syntax,
        String description
    ) {
        sender.sendMessage(
            Component.text()
                .append(
                    Component.text(
                        syntax,
                        NamedTextColor.GOLD
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
                        description,
                        NamedTextColor.GRAY
                    )
                )
                .build()
        );
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

    private void sendNotImplemented(
        CommandSender sender,
        String subcommand
    ) {
        sender.sendMessage(
            Component.text()
                .append(
                    Component.text(
                        "/hc "
                            + subcommand,
                        NamedTextColor.GOLD
                    )
                )
                .append(
                    Component.text(
                        " is not available yet.",
                        NamedTextColor.GRAY
                    )
                )
                .build()
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

    private Component divider() {
        return Component.text(
            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━",
            NamedTextColor.DARK_GRAY
        );
    }
}