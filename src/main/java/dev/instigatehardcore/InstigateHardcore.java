package dev.instigatehardcore;

import dev.instigatehardcore.command.HardcoreCommand;
import dev.instigatehardcore.command.HardcoreTabCompleter;
import dev.instigatehardcore.core.RunManager;
import dev.instigatehardcore.countdown.CountdownManager;
import dev.instigatehardcore.listener.DeathListener;
import dev.instigatehardcore.listener.PlayerJoinListener;
import dev.instigatehardcore.listener.PortalRoutingListener;
import dev.instigatehardcore.player.PlayerResetManager;
import dev.instigatehardcore.scoreboard.HardcoreScoreboardManager;
import dev.instigatehardcore.stats.StatsManager;
import dev.instigatehardcore.world.WorldCleanupManager;
import dev.instigatehardcore.world.WorldRecoveryManager;
import dev.instigatehardcore.world.WorldRotationManager;
import dev.instigatehardcore.world.WorldSetManager;
import dev.instigatehardcore.world.WorldStateStore;

import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;

public final class InstigateHardcore extends JavaPlugin {

    private static final int DEFAULT_COUNTDOWN_SECONDS =
        10;

    private static final long DEFAULT_SCOREBOARD_UPDATE_INTERVAL =
        20L;

    private static final int DEFAULT_PRELOAD_RADIUS_CHUNKS =
        1;

    private RunManager runManager;
    private StatsManager statsManager;

    private CountdownManager countdownManager;
    private HardcoreScoreboardManager scoreboardManager;
    private PlayerResetManager playerResetManager;

    private WorldStateStore worldStateStore;
    private WorldRecoveryManager worldRecoveryManager;

    private WorldSetManager worldSetManager;
    private WorldCleanupManager worldCleanupManager;
    private WorldRotationManager worldRotationManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        /*
         * Persistent player and campaign statistics must load
         * before world recovery.
         */
        if (!initializeStats()) {
            return;
        }

        /*
         * Recover persistent world state, then load the ACTIVE
         * and STANDBY attempt worlds.
         */
        if (!initializeWorldSets()) {
            return;
        }

        /*
         * Runtime gameplay only becomes ACTIVE after the persistent
         * world pipeline has been recovered successfully.
         */
        runManager =
            new RunManager();

        if (!runManager.startRun()) {
            getLogger().severe(
                "Failed to initialize hardcore run state."
            );

            getServer()
                .getPluginManager()
                .disablePlugin(this);

            return;
        }

        playerResetManager =
            new PlayerResetManager(
                this
            );

        initializeCountdown();
        initializeScoreboard();
        initializeWorldCleanup();
        initializeWorldRotation();

        registerListeners();

        /*
         * Phase 7A command framework.
         *
         * Registers:
         *
         * /hardcore
         * /hc
         */
        if (!initializeCommands()) {
            return;
        }

        if (scoreboardManager != null) {
            scoreboardManager.start();
        }

        registerExistingPlayers();

        logStartupState();
    }

    @Override
    public void onDisable() {
        if (countdownManager != null) {
            countdownManager.cancel();
        }

        if (scoreboardManager != null) {
            scoreboardManager.stop();
        }

        if (statsManager != null) {
            try {
                statsManager.save();
            } catch (
                IOException exception
            ) {
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
     * Loads persistent campaign/player statistics.
     */
    private boolean initializeStats() {
        Path statsPath =
            getDataFolder()
                .toPath()
                .resolve(
                    "stats.properties"
                );

        statsManager =
            new StatsManager(
                statsPath
            );

        try {
            statsManager.load();

            return true;
        } catch (
            IOException exception
        ) {
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
     * Performs crash/restart recovery first, then loads the
     * persistent ACTIVE and STANDBY WorldSets.
     */
    private boolean initializeWorldSets() {
        String lobbyWorldName =
            getConfig().getString(
                "worlds.lobby-world",
                "world"
            );

        if (
            lobbyWorldName == null
                || lobbyWorldName.isBlank()
        ) {
            getLogger().warning(
                "Invalid worlds.lobby-world value. "
                    + "Using \"world\"."
            );

            lobbyWorldName =
                "world";
        }

        int preloadRadius =
            getConfig().getInt(
                "worlds.preload-radius-chunks",
                DEFAULT_PRELOAD_RADIUS_CHUNKS
            );

        if (preloadRadius < 0) {
            getLogger().warning(
                "Invalid worlds.preload-radius-chunks value: "
                    + preloadRadius
                    + ". Using "
                    + DEFAULT_PRELOAD_RADIUS_CHUNKS
                    + "."
            );

            preloadRadius =
                DEFAULT_PRELOAD_RADIUS_CHUNKS;
        }

        worldStateStore =
            new WorldStateStore(
                getDataFolder()
                    .toPath()
                    .resolve(
                        "world-state.properties"
                    )
            );

        worldRecoveryManager =
            new WorldRecoveryManager(
                statsManager,
                worldStateStore
            );

        /*
         * Persistent state reconciliation happens before custom
         * campaign worlds are loaded.
         */
        try {
            WorldRecoveryManager.RecoveryResult recovery =
                worldRecoveryManager
                    .recover();

            if (
                recovery.completedIncompleteRotation()
            ) {
                getLogger().warning(
                    "[Instigate Cafe Hardcore] "
                        + "Recovered an interrupted world rotation."
                );
            }

            if (
                recovery.advancedStats()
            ) {
                getLogger().warning(
                    "[Instigate Cafe Hardcore] "
                        + "Reconciled campaign statistics to attempt #"
                        + recovery
                            .state()
                            .activeAttempt()
                        + "."
                );
            }

            getLogger().info(
                "[Instigate Cafe Hardcore] "
                    + "Persistent world state is STABLE at attempt #"
                    + recovery
                        .state()
                        .activeAttempt()
                    + "."
            );
        } catch (
            IOException exception
        ) {
            getLogger().severe(
                "Unable to recover persistent hardcore world state."
            );

            exception.printStackTrace();

            getServer()
                .getPluginManager()
                .disablePlugin(this);

            return false;
        }

        worldSetManager =
            new WorldSetManager(
                this,
                worldStateStore,
                lobbyWorldName,
                preloadRadius
            );

        try {
            worldSetManager.initialize(
                statsManager
                    .getCurrentAttempt()
            );

            return true;
        } catch (
            IOException exception
        ) {
            getLogger().severe(
                "Unable to initialize hardcore world pipeline."
            );

            exception.printStackTrace();

            getServer()
                .getPluginManager()
                .disablePlugin(this);

            return false;
        }
    }

    /**
     * Initializes the countdown that runs after an attempt ends.
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
     * Initializes the sidebar scoreboard when enabled.
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

            scoreboardManager =
                null;

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
     * Initializes retired-world unloading and deletion.
     */
    private void initializeWorldCleanup() {
        worldCleanupManager =
            new WorldCleanupManager(
                this,
                worldSetManager
            );
    }

    /**
     * Initializes seamless ACTIVE/STANDBY attempt rotation.
     */
    private void initializeWorldRotation() {
        worldRotationManager =
            new WorldRotationManager(
                this,
                runManager,
                statsManager,
                playerResetManager,
                worldSetManager,
                worldCleanupManager,
                scoreboardManager
            );
    }

    /**
     * Registers all Paper/Bukkit listeners.
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
                    playerResetManager,
                    worldSetManager,
                    worldRotationManager
                ),
                this
            );

        /*
         * Keep Nether and End travel isolated to the current
         * ACTIVE attempt.
         */
        getServer()
            .getPluginManager()
            .registerEvents(
                new PortalRoutingListener(
                    this,
                    runManager,
                    worldSetManager
                ),
                this
            );

        /*
         * PlayerJoinListener is responsible for:
         *
         * - registering player stats
         * - assigning scoreboard
         * - placing stale players into ACTIVE
         * - recovering players stuck in spectator mode
         */
        getServer()
            .getPluginManager()
            .registerEvents(
                new PlayerJoinListener(
                    this,
                    statsManager,
                    scoreboardManager,
                    runManager,
                    playerResetManager,
                    worldSetManager
                ),
                this
            );
    }

    /**
     * Registers Phase 7 command handling.
     */
    private boolean initializeCommands() {
        PluginCommand hardcoreCommand =
            getCommand(
                "hardcore"
            );

        if (hardcoreCommand == null) {
            getLogger().severe(
                "The \"hardcore\" command is missing from plugin.yml."
            );

            getServer()
                .getPluginManager()
                .disablePlugin(
                    this
                );

            return false;
        }

        HardcoreCommand executor =
            new HardcoreCommand();

        HardcoreTabCompleter tabCompleter =
            new HardcoreTabCompleter();

        hardcoreCommand.setExecutor(
            executor
        );

        hardcoreCommand.setTabCompleter(
            tabCompleter
        );

        getLogger().info(
            "Hardcore commands registered: /hardcore, /hc"
        );

        return true;
    }

    /**
     * Supports development reloads or other cases where players
     * are already online when plugin initialization completes.
     */
    private void registerExistingPlayers() {
        for (
            Player player :
            getServer()
                .getOnlinePlayers()
        ) {
            try {
                statsManager.ensurePlayer(
                    player.getUniqueId(),
                    player.getName()
                );
            } catch (
                IOException exception
            ) {
                getLogger().severe(
                    "Failed to register online player "
                        + player.getName()
                        + "."
                );

                exception.printStackTrace();
            }

            if (scoreboardManager != null) {
                scoreboardManager.assign(
                    player
                );
            }
        }

        if (scoreboardManager != null) {
            scoreboardManager.refresh();
        }
    }

    /**
     * Logs the final initialized state.
     */
    private void logStartupState() {
        getLogger().info(
            "Instigate Cafe Hardcore enabled."
        );

        getLogger().info(
            "Current attempt: #"
                + statsManager
                    .getCurrentAttempt()
        );

        getLogger().info(
            "Run state: "
                + runManager
                    .getState()
        );

        getLogger().info(
            "Reset countdown: "
                + countdownManager
                    .getDurationSeconds()
                + " seconds"
        );

        if (worldSetManager != null) {
            getLogger().info(
                "Active world attempt: #"
                    + worldSetManager
                        .getActiveWorldSet()
                        .attemptNumber()
            );

            if (
                worldSetManager
                    .getStandbyWorldSet()
                    != null
            ) {
                getLogger().info(
                    "Standby world attempt: #"
                        + worldSetManager
                            .getStandbyWorldSet()
                            .attemptNumber()
                );
            }
        }

        if (scoreboardManager != null) {
            getLogger().info(
                "Hardcore scoreboard enabled."
            );
        }

        getLogger().info(
            "Seamless world cleanup enabled."
        );

        getLogger().info(
            "Active-attempt portal routing enabled."
        );

        getLogger().info(
            "Crash/restart recovery enabled."
        );

        getLogger().info(
            "Hardcore command framework enabled."
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

    public HardcoreScoreboardManager getScoreboardManager() {
        return scoreboardManager;
    }

    public PlayerResetManager getPlayerResetManager() {
        return playerResetManager;
    }

    public WorldStateStore getWorldStateStore() {
        return worldStateStore;
    }

    public WorldRecoveryManager getWorldRecoveryManager() {
        return worldRecoveryManager;
    }

    public WorldSetManager getWorldSetManager() {
        return worldSetManager;
    }

    public WorldCleanupManager getWorldCleanupManager() {
        return worldCleanupManager;
    }

    public WorldRotationManager getWorldRotationManager() {
        return worldRotationManager;
    }
}