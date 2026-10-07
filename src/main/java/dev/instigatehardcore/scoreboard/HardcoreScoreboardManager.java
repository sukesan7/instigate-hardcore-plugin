package dev.instigatehardcore.scoreboard;

import dev.instigatehardcore.core.RunManager;
import dev.instigatehardcore.stats.PlayerStats;
import dev.instigatehardcore.stats.StatsManager;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class HardcoreScoreboardManager {

    private static final String OBJECTIVE_NAME =
        "instigate_hardcore";

    private static final int MAX_LINES = 15;

    private final JavaPlugin plugin;
    private final RunManager runManager;
    private final StatsManager statsManager;
    private final long updateIntervalTicks;

    private final Scoreboard scoreboard;
    private final Objective objective;

    private BukkitTask updateTask;

    public HardcoreScoreboardManager(
        JavaPlugin plugin,
        RunManager runManager,
        StatsManager statsManager,
        long updateIntervalTicks
    ) {
        this.plugin = Objects.requireNonNull(plugin);
        this.runManager = Objects.requireNonNull(runManager);
        this.statsManager = Objects.requireNonNull(statsManager);

        if (updateIntervalTicks < 1) {
            throw new IllegalArgumentException(
                "Scoreboard update interval must be at least one tick."
            );
        }

        this.updateIntervalTicks =
            updateIntervalTicks;

        org.bukkit.scoreboard.ScoreboardManager
            bukkitScoreboardManager =
                Objects.requireNonNull(
                    plugin.getServer()
                        .getScoreboardManager(),
                    "Bukkit ScoreboardManager is unavailable."
                );

        this.scoreboard =
            bukkitScoreboardManager.getNewScoreboard();

        this.objective =
            scoreboard.registerNewObjective(
                OBJECTIVE_NAME,
                Criteria.DUMMY,
                Component.text(
                    "INSTIGATE CAFE HARDCORE",
                    NamedTextColor.GOLD
                ).decorate(TextDecoration.BOLD)
            );

        objective.setDisplaySlot(
            DisplaySlot.SIDEBAR
        );

        /*
         * Hide Minecraft's normal numerical score values.
         *
         * We only use scores internally to control line order.
         */
        objective.numberFormat(
            NumberFormat.blank()
        );
    }

    public void start() {
        if (updateTask != null) {
            return;
        }

        refresh();

        updateTask = plugin
            .getServer()
            .getScheduler()
            .runTaskTimer(
                plugin,
                this::refresh,
                updateIntervalTicks,
                updateIntervalTicks
            );

        plugin.getLogger().info(
            "[Instigate Cafe Hardcore] "
                + "Scoreboard updater started."
        );
    }

    public void stop() {
        if (updateTask == null) {
            return;
        }

        updateTask.cancel();
        updateTask = null;

        plugin.getLogger().info(
            "[Instigate Cafe Hardcore] "
                + "Scoreboard updater stopped."
        );
    }

    public void assign(Player player) {
        Objects.requireNonNull(player);

        player.setScoreboard(
            scoreboard
        );
    }

    public void refresh() {
        List<Component> lines =
            buildLines();

        updateLines(lines);

        for (
            Player player :
            plugin.getServer().getOnlinePlayers()
        ) {
            if (player.getScoreboard() != scoreboard) {
                assign(player);
            }
        }
    }

    private List<Component> buildLines() {
        List<Component> lines =
            new ArrayList<>();

        lines.add(
            Component.empty()
        );

        lines.add(
            Component.text(
                "Attempt ",
                NamedTextColor.GRAY
            ).append(
                Component.text(
                    "#"
                        + statsManager
                            .getCurrentAttempt(),
                    NamedTextColor.RED
                ).decorate(
                    TextDecoration.BOLD
                )
            )
        );

        lines.add(
            Component.text(
                "Run ",
                NamedTextColor.GRAY
            ).append(
                Component.text(
                    formatDuration(
                        runManager
                            .getElapsedTime()
                    ),
                    NamedTextColor.WHITE
                )
            )
        );

        lines.add(
            Component.text(" ")
        );

        lines.add(
            Component.text(
                "DEATHS",
                NamedTextColor.GOLD
            ).decorate(
                TextDecoration.BOLD
            )
        );

        List<PlayerStats> players =
            statsManager.getPlayersByDeaths();

        int availablePlayerLines =
            MAX_LINES - lines.size();

        for (
            PlayerStats player :
            players.stream()
                .limit(availablePlayerLines)
                .toList()
        ) {
            lines.add(
                createPlayerLine(player)
            );
        }

        return lines;
    }

    private Component createPlayerLine(
        PlayerStats player
    ) {
        return Component.text(
            player.name(),
            NamedTextColor.WHITE
        ).append(
            Component.text(
                "  •  ",
                NamedTextColor.DARK_GRAY
            )
        ).append(
            Component.text(
                Integer.toString(
                    player.deaths()
                ),
                player.deaths() == 0
                    ? NamedTextColor.GRAY
                    : NamedTextColor.RED
            )
        );
    }

    private void updateLines(
        List<Component> lines
    ) {
        for (
            int index = 0;
            index < MAX_LINES;
            index++
        ) {
            String entry =
                entryFor(index);

            if (index >= lines.size()) {
                scoreboard.resetScores(
                    entry
                );

                continue;
            }

            /*
             * Higher score values render above lower values.
             */
            int scoreValue =
                MAX_LINES - index;

            Score score =
                objective.getScore(entry);

            score.setScore(
                scoreValue
            );

            score.customName(
                lines.get(index)
            );

            score.numberFormat(
                NumberFormat.blank()
            );
        }
    }

    private String entryFor(
        int index
    ) {
        /*
         * Internal unique entry IDs.
         *
         * Players never see these because customName()
         * determines the visible sidebar text.
         */
        return "ih_line_" + index;
    }

    private String formatDuration(
        Duration duration
    ) {
        long totalSeconds =
            Math.max(
                0,
                duration.getSeconds()
            );

        long hours =
            totalSeconds / 3600;

        long minutes =
            (totalSeconds % 3600) / 60;

        long seconds =
            totalSeconds % 60;

        return String.format(
            "%02d:%02d:%02d",
            hours,
            minutes,
            seconds
        );
    }

    public Scoreboard getScoreboard() {
        return scoreboard;
    }
}