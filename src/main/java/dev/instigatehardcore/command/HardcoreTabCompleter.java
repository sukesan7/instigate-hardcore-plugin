package dev.instigatehardcore.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class HardcoreTabCompleter
    implements TabCompleter {

    @Override
    public List<String> onTabComplete(
        CommandSender sender,
        Command command,
        String alias,
        String[] args
    ) {
        Objects.requireNonNull(sender);
        Objects.requireNonNull(command);
        Objects.requireNonNull(alias);
        Objects.requireNonNull(args);

        /*
         * /hc <TAB>
         */
        if (args.length == 1) {
            List<String> commands =
                new ArrayList<>();

            commands.add(
                "help"
            );

            commands.add(
                "status"
            );

            commands.add(
                "stats"
            );

            commands.add(
                "deaths"
            );

            if (
                sender.hasPermission(
                    "instigatehardcore.admin"
                )
            ) {
                commands.add(
                    "reset"
                );

                commands.add(
                    "worlds"
                );
            }

            if (
                sender.hasPermission(
                    "instigatehardcore.debug"
                )
            ) {
                commands.add(
                    "debug"
                );
            }

            return filter(
                commands,
                args[0]
            );
        }

        /*
         * Player completion for:
         *
         * /hc stats <player>
         *
         * We'll expand this later so offline historical players
         * can also appear.
         */
        if (
            args.length == 2
                && args[0].equalsIgnoreCase(
                    "stats"
                )
        ) {
            List<String> players =
                new ArrayList<>();

            sender.getServer()
                .getOnlinePlayers()
                .forEach(
                    player ->
                        players.add(
                            player.getName()
                        )
                );

            return filter(
                players,
                args[1]
            );
        }

        /*
         * Prevent Bukkit from inserting unrelated suggestions.
         */
        return List.of();
    }

    private List<String> filter(
        List<String> options,
        String input
    ) {
        String prefix =
            input.toLowerCase(
                Locale.ROOT
            );

        return options.stream()
            .filter(
                option ->
                    option
                        .toLowerCase(
                            Locale.ROOT
                        )
                        .startsWith(
                            prefix
                        )
            )
            .sorted(
                String.CASE_INSENSITIVE_ORDER
            )
            .toList();
    }
}