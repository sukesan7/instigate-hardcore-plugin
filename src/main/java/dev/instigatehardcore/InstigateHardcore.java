package dev.instigatehardcore;

import dev.instigatehardcore.core.RunManager;
import dev.instigatehardcore.listener.DeathListener;
import dev.instigatehardcore.stats.StatsManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;

public final class InstigateHardcore extends JavaPlugin {

    private RunManager runManager;
    private StatsManager statsManager;

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
    }

    @Override
    public void onDisable() {
        if (statsManager != null) {
            try {
                statsManager.save();
            } catch (IOException exception) {
                getLogger().severe(
                    "Failed to save persistent statistics during shutdown."
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

        statsManager = new StatsManager(statsPath);

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

    private void registerListeners() {
        getServer()
            .getPluginManager()
            .registerEvents(
                new DeathListener(
                    this,
                    runManager,
                    statsManager
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
}