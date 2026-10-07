package dev.instigatehardcore;

import dev.instigatehardcore.core.RunManager;
import dev.instigatehardcore.countdown.CountdownManager;
import dev.instigatehardcore.listener.DeathListener;
import dev.instigatehardcore.listener.PlayerJoinListener;
import dev.instigatehardcore.listener.PortalRoutingListener;
import dev.instigatehardcore.player.PlayerResetManager;
import dev.instigatehardcore.scoreboard.HardcoreScoreboardManager;
import dev.instigatehardcore.stats.StatsManager;
import dev.instigatehardcore.world.WorldCleanupManager;
import dev.instigatehardcore.world.WorldRotationManager;
import dev.instigatehardcore.world.WorldSetManager;
import dev.instigatehardcore.world.WorldStateStore;

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

    private WorldSetManager worldSetManager;
    private WorldCleanupManager worldCleanupManager;
    private WorldRotationManager worldRotationManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        /*
         * Stats establish which campaign attempt should currently
         * exist.
         */
        if (!initializeStats()) {
            return;
        }

        /*
         * Load/create:
         *
         * - permanent lobby
         * - ACTIVE attempt
         * - STANDBY attempt
         */
        if (!initializeWorldSets()) {
            return;
        }

        /*
         * Gameplay only becomes ACTIVE after the world pipeline
         * exists successfully.
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

        /*
         * Cleanup must exist before WorldRotationManager because
         * successful rotations immediately schedule retired-world
         * cleanup.
         */
        initializeWorldCleanup();
        initializeWorldRotation();

        registerListeners();

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

        WorldStateStore stateStore =
            new WorldStateStore(
                getDataFolder()
                    .toPath()
                    .resolve(
                        "world-state.properties"
                    )
            );

        worldSetManager =
            new WorldSetManager(
                this,
                stateStore,
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

    private void initializeWorldCleanup() {
        worldCleanupManager =
            new WorldCleanupManager(
                this,
                worldSetManager
            );
    }

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
         * Keep all portal travel inside the currently ACTIVE
         * hardcore attempt.
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
         * Always register this listener.
         *
         * Besides scoreboard assignment, it is responsible for
         * placing players into the current ACTIVE attempt.
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
     * Supports development reloads and other situations where
     * players are already online when the plugin initializes.
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