package dev.instigatehardcore;

import dev.instigatehardcore.command.HardcoreCommand;
import dev.instigatehardcore.command.HardcoreTabCompleter;

import dev.instigatehardcore.core.AttemptEndManager;
import dev.instigatehardcore.core.RunManager;

import dev.instigatehardcore.countdown.CountdownManager;

import dev.instigatehardcore.listener.DeathListener;
import dev.instigatehardcore.listener.PlayerJoinListener;
import dev.instigatehardcore.listener.PlayerQuitListener;
import dev.instigatehardcore.listener.PortalRoutingListener;

import dev.instigatehardcore.participation.AttemptParticipantManager;

import dev.instigatehardcore.player.PlayerResetManager;

import dev.instigatehardcore.replay.DeathReplayCaptureService;
import dev.instigatehardcore.replay.DeathReplayRecorder;
import dev.instigatehardcore.replay.ReplayCombatRecorder;
import dev.instigatehardcore.replay.ReplayDebugPreviewCommand;

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

    private AttemptEndManager attemptEndManager;

    // Phase 9A–9D: recording and optional in-game developer preview.
    private DeathReplayRecorder deathReplayRecorder;
    private ReplayCombatRecorder replayCombatRecorder;
    private DeathReplayCaptureService deathReplayCaptureService;
    private ReplayDebugPreviewCommand replayDebugPreviewCommand;

    private BukkitTask telemetryCheckpointTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        /*
         * Persistent campaign statistics.
         */
        if (!initializeStats()) {
            return;
        }

        /*
         * Crash-safe world recovery.
         */
        if (!initializeWorldSets()) {
            return;
        }

        /*
         * Per-attempt participation history.
         */
        if (!initializeParticipants()) {
            return;
        }

        /*
         * Player playtime and structured death history.
         */
        if (!initializeTelemetry()) {
            return;
        }

        /*
         * Runtime state becomes ACTIVE only after persistent
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

        /*
         * Shared death/admin reset pipeline.
         */
        initializeAttemptEnding();

        // Record active gameplay and freeze the confirmed death clip.
        // Neither service alters the existing countdown or world rotation.
        initializeDeathReplayRecorder();
        initializeDeathReplayCapture();

        registerListeners();

        if (!initializeCommands()) {
            return;
        }

        // Developer-only visual preview, available when PacketEvents loads.
        initializeReplayPreviewCommand();

        initializeTelemetryCheckpoint();

        if (scoreboardManager != null) {
            scoreboardManager.start();
        }

        registerExistingPlayers();

        logStartupState();
    }

    @Override
    public void onDisable() {
        // Dispose packet-only ghost entities before stopping the recorder.
        if (replayDebugPreviewCommand != null) {
            replayDebugPreviewCommand.close();
            replayDebugPreviewCommand = null;
        }

        if (deathReplayCaptureService != null) {
            deathReplayCaptureService.clear();
            deathReplayCaptureService = null;
        }

        if (deathReplayRecorder != null) {
            deathReplayRecorder.stop();
            deathReplayRecorder = null;
        }

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
         * Commit active playtime before normal shutdown.
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

    /*
     * ------------------------------------------------------------
     * STATS
     * ------------------------------------------------------------
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

            disableSelf();

            return false;
        }
    }

    /*
     * ------------------------------------------------------------
     * WORLD RECOVERY
     * ------------------------------------------------------------
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

    /*
     * ------------------------------------------------------------
     * PARTICIPANTS
     * ------------------------------------------------------------
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

    /*
     * ------------------------------------------------------------
     * TELEMETRY
     * ------------------------------------------------------------
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

    /*
     * ------------------------------------------------------------
     * GAMEPLAY SERVICES
     * ------------------------------------------------------------
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

    private void initializeAttemptEnding() {
        attemptEndManager =
            new AttemptEndManager(
                this,
                runManager,
                statsManager,
                telemetryManager,
                countdownManager,
                playerResetManager,
                worldRotationManager
            );
    }

    /*
     * ------------------------------------------------------------
     * PHASE 9: DEATH REPLAY RECORDING AND CAPTURE
     * ------------------------------------------------------------
     */

    private void initializeDeathReplayRecorder() {
        if (!getConfig().getBoolean("death-replay.enabled", true)) {
            getLogger().info("Death replay recording disabled by configuration.");
            return;
        }

        deathReplayRecorder = new DeathReplayRecorder(
            this,
            runManager,
            statsManager,
            worldSetManager,
            getConfig().getInt("death-replay.duration-seconds", 7),
            getConfig().getInt("death-replay.capture-interval-ticks", 2),
            getConfig().getInt("death-replay.capture-radius-blocks", 24),
            getConfig().getInt("death-replay.max-actors-per-frame", 48)
        );

        deathReplayRecorder.start();
        getLogger().info("Phase 9A rolling death replay recorder started.");
    }

    private void initializeDeathReplayCapture() {
        // Keep the core hardcore lifecycle functional when recording is off.
        if (deathReplayRecorder == null) {
            return;
        }

        replayCombatRecorder = new ReplayCombatRecorder(
            this,
            runManager,
            statsManager,
            worldSetManager,
            deathReplayRecorder,
            getConfig().getInt("death-replay.capture-radius-blocks", 24)
        );

        getServer().getPluginManager().registerEvents(
            replayCombatRecorder,
            this
        );

        deathReplayCaptureService = new DeathReplayCaptureService(
            this,
            deathReplayRecorder,
            replayCombatRecorder
        );

        // Requires the Phase 9B setter in AttemptEndManager.java.
        attemptEndManager.setDeathReplayCaptureService(
            deathReplayCaptureService
        );

        getLogger().info("Phase 9B death replay capture enabled.");
    }

    /*
     * ------------------------------------------------------------
     * LISTENERS
     * ------------------------------------------------------------
     */

    private void registerListeners() {
        boolean allowLateJoiners =
            getConfig()
                .getBoolean(
                    "players.allow-late-joiners",
                    true
                );

        getLogger().info(
            "[Instigate Cafe] Late joining is "
                + (
                    allowLateJoiners
                        ? "enabled."
                        : "disabled."
                )
        );

        getServer()
            .getPluginManager()
            .registerEvents(
                new DeathListener(
                    worldSetManager,
                    attemptEndManager
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
                    telemetryManager,
                    allowLateJoiners
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

    /*
     * ------------------------------------------------------------
     * COMMANDS
     * ------------------------------------------------------------
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

        HardcoreCommand executor =
            new HardcoreCommand(
                runManager,
                statsManager,
                participantManager,
                telemetryManager,
                worldSetManager,
                worldRotationManager,
                worldCleanupManager,
                countdownManager,
                worldStateStore,
                attemptEndManager
            );

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

    /*
     * ------------------------------------------------------------
     * PHASE 9D: OPTIONAL REPLAY PREVIEW COMMAND
     * ------------------------------------------------------------
     */

    private void initializeReplayPreviewCommand() {
        if (deathReplayRecorder == null) {
            getLogger().info(
                "Replay preview unavailable: recording is disabled."
            );
            return;
        }

        // Never load the packet renderer when its plugin is absent.
        if (!getServer().getPluginManager().isPluginEnabled("packetevents")) {
            getLogger().warning(
                "Replay preview unavailable: PacketEvents is not enabled."
            );
            return;
        }

        PluginCommand command = getCommand("hcreplaytest");
        if (command == null) {
            getLogger().warning(
                "Missing hcreplaytest command declaration in plugin.yml."
            );
            return;
        }

        replayDebugPreviewCommand = new ReplayDebugPreviewCommand(
            this,
            deathReplayRecorder
        );

        command.setExecutor(replayDebugPreviewCommand);
        getServer().getPluginManager().registerEvents(
            replayDebugPreviewCommand,
            this
        );

        getLogger().info("Phase 9D replay preview command registered.");
    }

    /*
     * ------------------------------------------------------------
     * EXISTING PLAYERS
     * ------------------------------------------------------------
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

    /*
     * ------------------------------------------------------------
     * STARTUP LOGGING
     * ------------------------------------------------------------
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
            "Shared attempt-ending pipeline enabled."
        );

        getLogger().info(
            "World diagnostics enabled."
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

    /*
     * ------------------------------------------------------------
     * GETTERS
     * ------------------------------------------------------------
     */

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

    public AttemptEndManager getAttemptEndManager() {
        return attemptEndManager;
    }
}