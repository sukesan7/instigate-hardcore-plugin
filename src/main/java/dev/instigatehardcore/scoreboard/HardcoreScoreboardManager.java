package dev.instigatehardcore.scoreboard;

import dev.instigatehardcore.core.RunManager;
import dev.instigatehardcore.stats.PlayerStats;
import dev.instigatehardcore.stats.StatsManager;
import dev.instigatehardcore.ui.InstigateTheme;

import io.papermc.paper.scoreboard.numbers.NumberFormat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import java.time.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class HardcoreScoreboardManager {

    // Vanilla sidebar supports 15 lines. Four are fixed metadata/headers,
    // leaving eleven for players. Larger histories rotate through pages.
    private static final int MAX_DEATH_ROWS = 11;

    private static final String OBJECTIVE_NAME =
        "instigate_hc";

    private final JavaPlugin plugin;

    private final RunManager runManager;
    private final StatsManager statsManager;

    private final long updateIntervalTicks;

    private Scoreboard scoreboard;
    private Objective objective;

    private BukkitTask updateTask;

    public HardcoreScoreboardManager(
        JavaPlugin plugin,
        RunManager runManager,
        StatsManager statsManager,
        long updateIntervalTicks
    ) {
        this.plugin =
            Objects.requireNonNull(
                plugin
            );

        this.runManager =
            Objects.requireNonNull(
                runManager
            );

        this.statsManager =
            Objects.requireNonNull(
                statsManager
            );

        if (updateIntervalTicks < 1L) {
            throw new IllegalArgumentException(
                "Scoreboard update interval must be at least one tick."
            );
        }

        this.updateIntervalTicks =
            updateIntervalTicks;
    }

    /*
     * ------------------------------------------------------------
     * LIFECYCLE
     * ------------------------------------------------------------
     */

    public void start() {
        if (updateTask != null) {
            return;
        }

        ensureScoreboard();

        for (
            Player player :
            Bukkit.getOnlinePlayers()
        ) {
            assign(
                player
            );
        }

        refresh();

        updateTask =
            Bukkit.getScheduler()
                .runTaskTimer(
                    plugin,
                    this::refresh,
                    updateIntervalTicks,
                    updateIntervalTicks
                );
    }

    public void stop() {
        if (updateTask != null) {
            updateTask.cancel();

            updateTask =
                null;
        }

        if (scoreboard == null) {
            return;
        }

        ScoreboardManager scoreboardManager =
            Bukkit.getScoreboardManager();

        if (scoreboardManager == null) {
            return;
        }

        Scoreboard main =
            scoreboardManager
                .getMainScoreboard();

        for (
            Player player :
            Bukkit.getOnlinePlayers()
        ) {
            if (
                player.getScoreboard()
                    == scoreboard
            ) {
                player.setScoreboard(
                    main
                );
            }
        }
    }

    /*
     * ------------------------------------------------------------
     * PLAYER ASSIGNMENT
     * ------------------------------------------------------------
     */

    public void assign(
        Player player
    ) {
        Objects.requireNonNull(
            player
        );

        ensureScoreboard();

        player.setScoreboard(
            scoreboard
        );
    }

    /*
     * ------------------------------------------------------------
     * REFRESH
     * ------------------------------------------------------------
     */

    public void refresh() {
        ensureScoreboard();

        /*
         * Remove all old rows before rebuilding the compact view.
         */
        for (
            String entry :
            new ArrayList<>(
                scoreboard.getEntries()
            )
        ) {
            scoreboard.resetScores(
                entry
            );
        }

        List<Component> lines =
            buildLines();

        int scoreValue =
            lines.size();

        for (
            int index = 0;
            index < lines.size();
            index++
        ) {
            /*
             * Each underlying score entry must be unique.
             *
             * The player never sees this key because Paper's
             * customName component replaces its visible text.
             */
            String entry =
                "ihc_line_"
                    + index;

            Score score =
                objective.getScore(
                    entry
                );

            score.setScore(
                scoreValue--
            );

            score.customName(
                lines.get(
                    index
                )
            );
        }
    }

    private List<Component> buildLines() {
        List<Component> lines =
            new ArrayList<>();

        /*
         * Attempt
         */
        lines.add(
            Component.text()
                .append(
                    InstigateTheme.secondary(
                        "Attempt  "
                    )
                )
                .append(
                    Component.text(
                        "#"
                            + statsManager
                                .getCurrentAttempt(),
                        InstigateTheme.PURPLE
                    )
                )
                .build()
        );

        /*
         * Run timer
         */
        lines.add(
            Component.text()
                .append(
                    InstigateTheme.secondary(
                        "Run  "
                    )
                )
                .append(
                    Component.text(
                        formatDuration(
                            runManager
                                .getElapsedTime()
                        ),
                        InstigateTheme.TEXT
                    )
                )
                .build()
        );

        /*
         * Spacer.
         */
        lines.add(
            Component.text(
                " "
            )
        );

        /*
         * Death leaderboard heading.
         */
        lines.add(
            Component.text(
                "Deaths",
                InstigateTheme.PURPLE
            ).decorate(
                TextDecoration.BOLD
            )
        );

        List<PlayerStats> players =
            statsManager
                .getPlayersByDeaths();

        if (players.isEmpty()) {
            lines.add(
                InstigateTheme.muted(
                    "No players yet"
                )
            );

            return lines;
        }

        int pageCount = Math.max(1,
            (players.size() + MAX_DEATH_ROWS - 1) / MAX_DEATH_ROWS
        );
        int intervalSeconds = Math.max(1,
            plugin.getConfig().getInt("scoreboard.page-interval-seconds", 10)
        );
        int page = (int) ((Bukkit.getCurrentTick() / (20L * intervalSeconds))
            % pageCount);
        int first = page * MAX_DEATH_ROWS;
        int last = Math.min(first + MAX_DEATH_ROWS, players.size());

        // Reuse the existing heading without spending another sidebar row.
        if (pageCount > 1) {
            lines.set(3, Component.text("Deaths " + (page + 1) + "/" + pageCount,
                InstigateTheme.PURPLE).decorate(TextDecoration.BOLD));
        }

        for (int index = first; index < last; index++) {
            PlayerStats player = players.get(index);
            lines.add(
                Component.text()
                    .append(Component.text(player.name(), InstigateTheme.TEXT))
                    .append(InstigateTheme.muted("  "))
                    .append(Component.text(Integer.toString(player.deaths()),
                        player.deaths() > 0 ? InstigateTheme.PURPLE : InstigateTheme.MUTED))
                    .build()
            );
        }

        return lines;
    }

    /*
     * ------------------------------------------------------------
     * SCOREBOARD CREATION
     * ------------------------------------------------------------
     */

    private void ensureScoreboard() {
        if (
            scoreboard != null
                && objective != null
        ) {
            return;
        }

        ScoreboardManager scoreboardManager =
            Bukkit.getScoreboardManager();

        if (scoreboardManager == null) {
            throw new IllegalStateException(
                "Bukkit scoreboard manager is unavailable."
            );
        }

        scoreboard =
            scoreboardManager
                .getNewScoreboard();

        objective =
            scoreboard
                .registerNewObjective(
                    OBJECTIVE_NAME,
                    Criteria.DUMMY,
                    InstigateTheme.brand()
                );

        objective.setDisplaySlot(
            DisplaySlot.SIDEBAR
        );

        /*
         * The sidebar uses score values only to determine row
         * ordering. They should not be shown to players.
         */
        objective.numberFormat(
            NumberFormat.blank()
        );
    }

    /*
     * ------------------------------------------------------------
     * FORMATTING
     * ------------------------------------------------------------
     */

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
}