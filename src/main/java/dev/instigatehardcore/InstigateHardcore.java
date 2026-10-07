package dev.instigatehardcore;

import org.bukkit.plugin.java.JavaPlugin;

public final class InstigateHardcore extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();

        getLogger().info("InstigateHardcore enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("InstigateHardcore disabled.");
    }
}
