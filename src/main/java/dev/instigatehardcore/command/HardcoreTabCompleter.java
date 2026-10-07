package dev.instigatehardcore.command;

import dev.instigatehardcore.stats.StatsManager;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class HardcoreTabCompleter
    implements TabCompleter {

    private final StatsManager statsManager;

    public HardcoreTabCompleter(
        StatsManager statsManager
    ) {
        this.statsManager =
            Objects.requireNonNull(
                statsManager
            );
    }

    @Override
    public List<String> onTabComplete(
        CommandSender sender,
        Command command,
        String alias,
        String[] args
    ) {
        Objects.requireNonNull(
            sender
        );

        Objects.requireNonNull(
            command
        );

        Objects.requireNonNull(
            alias
        );

        Objects.requireNonNull(
            args
        );

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
         * /hc stats <player>
         *
         * Use persistent StatsManager records rather than only
         * currently-online players so historical/offline players
         * can also be tab-completed.
         */
        if (
            args.length == 2
                && args[0].equalsIgnoreCase(
                    "stats"
                )
        ) {
            List<String> players =
                statsManager
                    .getPlayersByDeaths()
                    .stream()
                    .map(
                        player ->
                            player.name()
                    )
                    .distinct()
                    .toList();

            return filter(
                players,
                args[1]
            );
        }

        /*
         * /hc reset <TAB>
         *
         * Only administrators should ever see the confirmation
         * suggestion.
         */
        if (
            args.length == 2
                && args[0].equalsIgnoreCase(
                    "reset"
                )
                && sender.hasPermission(
                    "instigatehardcore.admin"
                )
        ) {
            return filter(
                List.of(
                    "confirm"
                ),
                args[1]
            );
        }

        /*
         * Prevent Bukkit from suggesting unrelated values for
         * commands that do not currently have more arguments.
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