package dev.instigatehardcore;

import dev.instigatehardcore.command.HardcoreCommand;
import dev.instigatehardcore.command.HardcoreTabCompleter;

import dev.instigatehardcore.core.RunManager;
import dev.instigatehardcore.countdown.CountdownManager;

import dev.instigatehardcore.listener.DeathListener;
import dev.instigatehardcore.listener.PlayerJoinListener;
import dev.instigatehardcore.listener.PlayerQuitListener;
import dev.instigatehardcore.listener.PortalRoutingListener;

import dev.instigatehardcore.participation.AttemptParticipantManager;

import dev.instigatehardcore.player.PlayerResetManager;
import dev.instigatehardcore.scoreboard.HardcoreScoreboardManager;
import dev.instigatehardcore.stats.StatsManager;

import dev.instigatehardcore.telemetry.PlayerTelemetryManager;

import dev.instigatehardcore.world.WorldCleanupManager;
import dev.instigatehardcore.world.WorldRecoveryManager;
import dev.instigatehardcore.world.WorldRotationManager;
import dev.instigatehardcore.world.WorldSetManager;
import dev.instigatehardcore.world.WorldStateStore;

import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Path;

public final class InstigateHardcore extends JavaPlugin {

    private static final int DEFAULT_COUNTDOWN_SECONDS =
        10;

    private static final long DEFAULT_SCOREBOARD_UPDATE_INTERVAL =
        20L;

    private static final int DEFAULT_PRELOAD_RADIUS_CHUNKS =
        1;

    /*
     * Live player telemetry is checkpointed every minute.
     *
     * Normal shutdown commits everything immediately.
     * A hard process crash should therefore lose at most roughly
     * one checkpoint interval of playtime.
     */
    private static final long TELEMETRY_CHECKPOINT_INTERVAL_TICKS =
        20L * 60L;

    private RunManager runManager;

    private StatsManager statsManager;
    private AttemptParticipantManager participantManager;
    private PlayerTelemetryManager telemetryManager;

    private CountdownManager countdownManager;

    private HardcoreScoreboardManager scoreboardManager;
    private PlayerResetManager playerResetManager;

    private WorldStateStore worldStateStore;
    private WorldRecoveryManager worldRecoveryManager;

    private WorldSetManager worldSetManager;
    private WorldCleanupManager worldCleanupManager;
    private WorldRotationManager worldRotationManager;

    private BukkitTask telemetryCheckpointTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        /*
         * Persistent campaign statistics must initialize first
         * because Phase 6 recovery depends on the attempt number.
         */
        if (!initializeStats()) {
            return;
        }

        /*
         * Recover the authoritative ACTIVE/STANDBY world state.
         */
        if (!initializeWorldSets()) {
            return;
        }

        /*
         * Attempt participation is initialized after recovery
         * because recovery may advance the current attempt.
         */
        if (!initializeParticipants()) {
            return;
        }

        /*
         * Rich player telemetry is independent from critical
         * StatsManager campaign persistence.
         */
        if (!initializeTelemetry()) {
            return;
        }

        /*
         * Runtime state only becomes ACTIVE after all persistent
         * campaign state has initialized successfully.
         */
        runManager =
            new RunManager();

        if (!runManager.startRun()) {
            getLogger().severe(
                "Failed to initialize hardcore run state."
            );

            disableSelf();

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

        if (!initializeCommands()) {
            return;
        }

        initializeTelemetryCheckpoint();

        if (scoreboardManager != null) {
            scoreboardManager.start();
        }

        registerExistingPlayers();

        logStartupState();
    }

    @Override
    public void onDisable() {
        if (telemetryCheckpointTask != null) {
            telemetryCheckpointTask.cancel();

            telemetryCheckpointTask =
                null;
        }

        if (countdownManager != null) {
            countdownManager.cancel();
        }

        if (scoreboardManager != null) {
            scoreboardManager.stop();
        }

        /*
         * Commit any currently running playtime sessions before
         * the server exits.
         */
        if (telemetryManager != null) {
            try {
                telemetryManager
                    .closeAllSessions();
            } catch (
                IOException exception
            ) {
                getLogger().severe(
                    "Failed to finalize player telemetry "
                        + "during shutdown."
                );

                exception.printStackTrace();
            }
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

        if (participantManager != null) {
            try {
                participantManager.save();
            } catch (
                IOException exception
            ) {
                getLogger().severe(
                    "Failed to save attempt participation history "
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

            disableSelf();

            return false;
        }
    }

    /**
     * Performs Phase 6 crash/restart recovery and then loads
     * the authoritative ACTIVE and STANDBY WorldSets.
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

            disableSelf();

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

            disableSelf();

            return false;
        }
    }

    /**
     * Loads persistent per-attempt participation history.
     */
    private boolean initializeParticipants() {
        participantManager =
            new AttemptParticipantManager(
                getDataFolder()
                    .toPath()
                    .resolve(
                        "attempt-participants.properties"
                    )
            );

        try {
            participantManager.load();

            /*
             * Important:
             *
             * Do not clear the current participant set during a
             * normal restart. ensureAttempt() only creates it when
             * it does not already exist.
             */
            participantManager.ensureAttempt(
                statsManager
                    .getCurrentAttempt()
            );

            return true;
        } catch (
            IOException exception
        ) {
            getLogger().severe(
                "Unable to load attempt participation history."
            );

            exception.printStackTrace();

            disableSelf();

            return false;
        }
    }

    /**
     * Loads persistent playtime and structured death telemetry.
     */
    private boolean initializeTelemetry() {
        telemetryManager =
            new PlayerTelemetryManager(
                getDataFolder()
                    .toPath()
                    .resolve(
                        "player-telemetry.yml"
                    )
            );

        try {
            telemetryManager.load();

            return true;
        } catch (
            IOException exception
        ) {
            getLogger().severe(
                "Unable to load persistent player telemetry."
            );

            exception.printStackTrace();

            disableSelf();

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
                participantManager,
                telemetryManager,
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
                    telemetryManager,
                    countdownManager,
                    playerResetManager,
                    worldSetManager,
                    worldRotationManager
                ),
                this
            );

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

        getServer()
            .getPluginManager()
            .registerEvents(
                new PlayerJoinListener(
                    this,
                    statsManager,
                    scoreboardManager,
                    runManager,
                    playerResetManager,
                    worldSetManager,
                    participantManager,
                    telemetryManager
                ),
                this
            );

        getServer()
            .getPluginManager()
            .registerEvents(
                new PlayerQuitListener(
                    this,
                    telemetryManager
                ),
                this
            );
    }

    /**
     * Registers:
     *
     * /hardcore
     * /hc
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

            disableSelf();

            return false;
        }

        /*
         * Phase 7B command implementation.
         *
         * HardcoreCommand now has access to telemetry so it can
         * provide:
         *
         * /hc status
         * /hc stats
         * /hc stats <player>
         * /hc deaths
         */
        HardcoreCommand executor =
            new HardcoreCommand(
                runManager,
                statsManager,
                participantManager,
                telemetryManager,
                worldSetManager,
                worldRotationManager,
                worldCleanupManager
            );

        /*
         * Historical StatsManager players are used for
         * /hc stats <TAB>, allowing offline participants to appear
         * in completion suggestions.
         */
        HardcoreTabCompleter tabCompleter =
            new HardcoreTabCompleter(
                statsManager
            );

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
     * Periodically commits active playtime without ending the
     * sessions.
     */
    private void initializeTelemetryCheckpoint() {
        telemetryCheckpointTask =
            getServer()
                .getScheduler()
                .runTaskTimer(
                    this,
                    () -> {
                        try {
                            telemetryManager
                                .checkpointActiveSessions();
                        } catch (
                            IOException exception
                        ) {
                            getLogger().severe(
                                "Failed to checkpoint player telemetry."
                            );

                            exception.printStackTrace();
                        }
                    },
                    TELEMETRY_CHECKPOINT_INTERVAL_TICKS,
                    TELEMETRY_CHECKPOINT_INTERVAL_TICKS
                );
    }

    /**
     * Primarily protects development/plugin-reload scenarios.
     *
     * Under a normal server startup players usually join after
     * onEnable() and PlayerJoinListener handles this instead.
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

            if (
                runManager.isActive()
                    && worldSetManager.isActiveWorld(
                        player.getWorld()
                    )
            ) {
                try {
                    participantManager
                        .recordParticipant(
                            statsManager
                                .getCurrentAttempt(),
                            player.getUniqueId(),
                            player.getName()
                        );
                } catch (
                    IOException exception
                ) {
                    getLogger().severe(
                        "Failed to record participation for "
                            + player.getName()
                            + "."
                    );

                    exception.printStackTrace();
                }

                telemetryManager.beginSession(
                    statsManager
                        .getCurrentAttempt(),
                    player.getUniqueId(),
                    player.getName()
                );
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
            "Current attempt participants: "
                + participantManager
                    .getParticipantCount(
                        statsManager
                            .getCurrentAttempt()
                    )
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
            "Attempt participation tracking enabled."
        );

        getLogger().info(
            "Player telemetry tracking enabled."
        );

        getLogger().info(
            "Hardcore command framework enabled."
        );
    }

    private void disableSelf() {
        getServer()
            .getPluginManager()
            .disablePlugin(
                this
            );
    }

    public RunManager getRunManager() {
        return runManager;
    }

    public StatsManager getStatsManager() {
        return statsManager;
    }

    public AttemptParticipantManager getParticipantManager() {
        return participantManager;
    }

    public PlayerTelemetryManager getTelemetryManager() {
        return telemetryManager;
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