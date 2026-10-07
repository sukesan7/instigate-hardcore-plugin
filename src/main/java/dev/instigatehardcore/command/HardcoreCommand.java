package dev.instigatehardcore.command;

import dev.instigatehardcore.core.RunManager;
import dev.instigatehardcore.participation.AttemptParticipant;
import dev.instigatehardcore.participation.AttemptParticipantManager;
import dev.instigatehardcore.stats.StatsManager;
import dev.instigatehardcore.world.WorldCleanupManager;
import dev.instigatehardcore.world.WorldRotationManager;
import dev.instigatehardcore.world.WorldSet;
import dev.instigatehardcore.world.WorldSetManager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.time.Duration;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class HardcoreCommand
    implements CommandExecutor {

    private final RunManager runManager;
    private final StatsManager statsManager;

    private final AttemptParticipantManager participantManager;

    private final WorldSetManager worldSetManager;
    private final WorldRotationManager worldRotationManager;
    private final WorldCleanupManager worldCleanupManager;

    public HardcoreCommand(
        RunManager runManager,
        StatsManager statsManager,
        AttemptParticipantManager participantManager,
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
                sendNotImplemented(
                    sender,
                    "stats"
                );

                yield true;
            }

            case "deaths" -> {
                sendNotImplemented(
                    sender,
                    "deaths"
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

        Component divider =
            divider();

        sender.sendMessage(
            Component.empty()
        );

        sender.sendMessage(
            divider
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
            divider
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

    private void sendHelp(
        CommandSender sender
    ) {
        Component divider =
            divider();

        sender.sendMessage(
            Component.empty()
        );

        sender.sendMessage(
            divider
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
            divider
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