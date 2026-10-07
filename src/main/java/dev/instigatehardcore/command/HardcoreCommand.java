package dev.instigatehardcore.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.Locale;
import java.util.Objects;

public final class HardcoreCommand implements CommandExecutor {

    private static final String BASE_PERMISSION =
        "instigatehardcore.command";

    @Override
    public boolean onCommand(
        CommandSender sender,
        Command command,
        String label,
        String[] args
    ) {
        Objects.requireNonNull(sender);
        Objects.requireNonNull(command);
        Objects.requireNonNull(label);
        Objects.requireNonNull(args);

        /*
         * /hc
         * /hardcore
         *
         * Both display the help page.
         */
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subcommand =
            args[0].toLowerCase(
                Locale.ROOT
            );

        return switch (subcommand) {
            case "help" -> {
                sendHelp(sender);
                yield true;
            }

            /*
             * These commands will be implemented during the
             * remaining Phase 7 steps.
             *
             * Keeping their dispatcher entries here gives us one
             * authoritative command surface from the beginning.
             */
            case "status" -> {
                sendNotImplemented(
                    sender,
                    "status"
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

    private void sendHelp(
        CommandSender sender
    ) {
        Component divider =
            Component.text(
                "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━",
                NamedTextColor.DARK_GRAY
            );

        Component brand =
            Component.text(
                "INSTIGATE CAFE HARDCORE",
                NamedTextColor.GOLD
            ).decorate(
                TextDecoration.BOLD
            );

        Component subtitle =
            Component.text(
                "COMMANDS",
                NamedTextColor.RED
            );

        sender.sendMessage(
            Component.empty()
        );

        sender.sendMessage(
            divider
        );

        sender.sendMessage(
            brand
        );

        sender.sendMessage(
            subtitle
        );

        sender.sendMessage(
            Component.empty()
        );

        /*
         * Public commands.
         */
        sendHelpEntry(
            sender,
            "/hc help",
            "Show this command page."
        );

        sendHelpEntry(
            sender,
            "/hc status",
            "Show the current hardcore attempt and participants."
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

        /*
         * Only show administrative commands to people who
         * actually have access to them.
         */
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
        Component message =
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
                .build();

        sender.sendMessage(
            message
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
}