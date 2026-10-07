package dev.instigatehardcore;

import dev.instigatehardcore.core.RunManager;
import dev.instigatehardcore.countdown.CountdownManager;
import dev.instigatehardcore.listener.DeathListener;
import dev.instigatehardcore.stats.StatsManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;

public final class InstigateHardcore extends JavaPlugin {

    private static final int DEFAULT_COUNTDOWN_SECONDS = 10;

    private RunManager runManager;
    private StatsManager statsManager;
    private CountdownManager countdownManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (!initializeStats()) {
            return;
        }

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

        initializeCountdown();
        registerListeners();

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
    }

    @Override
    public void onDisable() {
        if (countdownManager != null) {
            countdownManager.cancel();
        }

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

    private boolean initializeStats() {
        Path statsPath = getDataFolder()
            .toPath()
            .resolve("stats.properties");

        statsManager = new StatsManager(
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

    private void initializeCountdown() {
        int configuredSeconds = getConfig().getInt(
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

    private void registerListeners() {
        getServer()
            .getPluginManager()
            .registerEvents(
                new DeathListener(
                    this,
                    runManager,
                    statsManager,
                    countdownManager
                ),
                this
            );
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
}