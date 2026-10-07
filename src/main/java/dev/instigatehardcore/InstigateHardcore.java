package dev.instigatehardcore;

import dev.instigatehardcore.core.RunManager;
import dev.instigatehardcore.countdown.CountdownManager;
import dev.instigatehardcore.listener.DeathListener;
import dev.instigatehardcore.listener.PlayerJoinListener;
import dev.instigatehardcore.player.PlayerResetManager;
import dev.instigatehardcore.scoreboard.HardcoreScoreboardManager;
import dev.instigatehardcore.stats.StatsManager;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;

public final class InstigateHardcore extends JavaPlugin {

    private static final int DEFAULT_COUNTDOWN_SECONDS = 10;
    private static final long DEFAULT_SCOREBOARD_UPDATE_INTERVAL = 20L;

    private RunManager runManager;
    private StatsManager statsManager;
    private CountdownManager countdownManager;
    private HardcoreScoreboardManager scoreboardManager;
    private PlayerResetManager playerResetManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        /*
         * Persistent campaign data must be available before
         * any gameplay systems are initialized.
         */
        if (!initializeStats()) {
            return;
        }

        /*
         * Create and activate the current hardcore run.
         */
        runManager = new RunManager();

        if (!runManager.startRun()) {
            getLogger().severe(
                "Failed to initialize hardcore run state."
            );

            getServer()
                .getPluginManager()
                .disablePlugin(this);

            return;
        }

        /*
         * Handles per-player cleanup and spectator transitions
         * between hardcore attempts.
         */
        playerResetManager =
            new PlayerResetManager(
                this
            );

        initializeCountdown();
        initializeScoreboard();
        registerListeners();

        /*
         * Start the scoreboard after all core systems and
         * listeners have been initialized.
         */
        if (scoreboardManager != null) {
            scoreboardManager.start();

            /*
             * Normally nobody should already be online when the
             * plugin first loads, but this also supports development
             * reload scenarios.
             */
            registerExistingPlayers();
        }

        getLogger().info(
            "Instigate Cafe Hardcore enabled."
        );

        getLogger().info(
            "Current attempt: #"
                + statsManager.getCurrentAttempt()
        );

        getLogger().info(
            "Run state: "
                + runManager.getState()
        );

        getLogger().info(
            "Reset countdown: "
                + countdownManager.getDurationSeconds()
                + " seconds"
        );

        if (scoreboardManager != null) {
            getLogger().info(
                "Hardcore scoreboard enabled."
            );
        }
    }

    @Override
    public void onDisable() {
        /*
         * Stop scheduled systems before saving persistent state.
         */
        if (countdownManager != null) {
            countdownManager.cancel();
        }

        if (scoreboardManager != null) {
            scoreboardManager.stop();
        }

        /*
         * Make one final attempt to persist campaign statistics.
         */
        if (statsManager != null) {
            try {
                statsManager.save();
            } catch (IOException exception) {
                getLogger().severe(
                    "Failed to save persistent statistics "
                        + "during shutdown."
                );

                exception.printStackTrace();
            }
        }

        getLogger().info(
            "Instigate Cafe Hardcore disabled."
        );
    }

    /**
     * Initializes persistent campaign statistics.
     *
     * @return true if statistics loaded successfully
     */
    private boolean initializeStats() {
        Path statsPath =
            getDataFolder()
                .toPath()
                .resolve("stats.properties");

        statsManager =
            new StatsManager(
                statsPath
            );

        try {
            statsManager.load();

            return true;
        } catch (IOException exception) {
            getLogger().severe(
                "Unable to load persistent hardcore statistics."
            );

            exception.printStackTrace();

            getServer()
                .getPluginManager()
                .disablePlugin(this);

            return false;
        }
    }

    /**
     * Initializes the run-ending countdown using config.yml.
     */
    private void initializeCountdown() {
        int configuredSeconds =
            getConfig().getInt(
                "reset.countdown-seconds",
                DEFAULT_COUNTDOWN_SECONDS
            );

        if (configuredSeconds < 1) {
            getLogger().warning(
                "Invalid reset.countdown-seconds value: "
                    + configuredSeconds
                    + ". Using default of "
                    + DEFAULT_COUNTDOWN_SECONDS
                    + "."
            );

            configuredSeconds =
                DEFAULT_COUNTDOWN_SECONDS;
        }

        countdownManager =
            new CountdownManager(
                this,
                configuredSeconds
            );
    }

    /**
     * Initializes the sidebar scoreboard if enabled.
     */
    private void initializeScoreboard() {
        boolean enabled =
            getConfig().getBoolean(
                "scoreboard.enabled",
                true
            );

        if (!enabled) {
            getLogger().info(
                "Hardcore scoreboard disabled by configuration."
            );

            scoreboardManager = null;
            return;
        }

        long updateInterval =
            getConfig().getLong(
                "scoreboard.update-interval-ticks",
                DEFAULT_SCOREBOARD_UPDATE_INTERVAL
            );

        if (updateInterval < 1) {
            getLogger().warning(
                "Invalid scoreboard update interval: "
                    + updateInterval
                    + ". Using "
                    + DEFAULT_SCOREBOARD_UPDATE_INTERVAL
                    + " ticks."
            );

            updateInterval =
                DEFAULT_SCOREBOARD_UPDATE_INTERVAL;
        }

        scoreboardManager =
            new HardcoreScoreboardManager(
                this,
                runManager,
                statsManager,
                updateInterval
            );
    }

    /**
     * Registers all Bukkit/Paper event listeners.
     */
    private void registerListeners() {
        getServer()
            .getPluginManager()
            .registerEvents(
                new DeathListener(
                    this,
                    runManager,
                    statsManager,
                    countdownManager,
                    playerResetManager
                ),
                this
            );

        if (scoreboardManager != null) {
            getServer()
                .getPluginManager()
                .registerEvents(
                    new PlayerJoinListener(
                        this,
                        statsManager,
                        scoreboardManager
                    ),
                    this
                );
        }
    }

    /**
     * Handles players who may already be online when the plugin
     * starts or is reloaded during development.
     */
    private void registerExistingPlayers() {
        if (scoreboardManager == null) {
            return;
        }

        for (
            Player player :
            getServer().getOnlinePlayers()
        ) {
            try {
                statsManager.ensurePlayer(
                    player.getUniqueId(),
                    player.getName()
                );
            } catch (IOException exception) {
                getLogger().severe(
                    "Failed to register online player "
                        + player.getName()
                        + "."
                );

                exception.printStackTrace();
            }

            scoreboardManager.assign(
                player
            );
        }

        scoreboardManager.refresh();
    }

    public RunManager getRunManager() {
        return runManager;
    }

    public StatsManager getStatsManager() {
        return statsManager;
    }

    public CountdownManager getCountdownManager() {
        return countdownManager;
    }

    public HardcoreScoreboardManager getScoreboardManager() {
        return scoreboardManager;
    }

    public PlayerResetManager getPlayerResetManager() {
        return playerResetManager;
    }
}