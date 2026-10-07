package dev.instigatehardcore;

import dev.instigatehardcore.core.RunManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class InstigateHardcore extends JavaPlugin {

    private RunManager runManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        runManager = new RunManager();

        if (!runManager.startRun()) {
            getLogger().severe("Failed to initialize run state.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getLogger().info("InstigateHardcore enabled.");
        getLogger().info("Run state: " + runManager.getState());
    }

    @Override
    public void onDisable() {
        getLogger().info("InstigateHardcore disabled.");
    }

    public RunManager getRunManager() {
        return runManager;
    }
}