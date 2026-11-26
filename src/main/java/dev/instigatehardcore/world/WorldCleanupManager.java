package dev.instigatehardcore.world;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class WorldCleanupManager {

    private static final long INITIAL_DELAY_TICKS = 20L;
    private static final long RETRY_DELAY_TICKS = 1L;

    /*
     * 100 ticks ~= 5 seconds.
     *
     * If we still cannot safely unload the retired world after
     * this long, something is wrong and we leave it intact.
     */
    private static final int MAX_RETRIES = 100;

    private static final long SHUTDOWN_WAIT_MILLIS = 5_000L;

    private final JavaPlugin plugin;
    private final WorldSetManager worldSetManager;
    private final Path worldContainer;
    private final Path pluginDataFolder;
    private final String pluginNamespace;

    private BukkitTask pendingUnloadTask;
    private boolean unloadInProgress;
    private int activeAsyncDeletions;
    private final Set<Integer> deletingAttempts = new HashSet<>();
    private volatile boolean shuttingDown;

    private record CleanupTarget(
        String dimension,
        World world,
        Path folder,
        WorldDeletionGuard.ExpectedWorldIdentity identity,
        boolean eligibleForDeletion
    ) {
    }

    public WorldCleanupManager(
        JavaPlugin plugin,
        WorldSetManager worldSetManager
    ) {
        this.plugin =
            Objects.requireNonNull(plugin);

        this.worldSetManager =
            Objects.requireNonNull(worldSetManager);

        this.worldContainer =
            plugin.getServer()
                .getWorldContainer()
                .toPath()
                .toAbsolutePath()
                .normalize();

        this.pluginDataFolder =
            plugin.getDataFolder()
                .toPath()
                .toAbsolutePath()
                .normalize();

        this.pluginNamespace =
            plugin.getName().toLowerCase(Locale.ROOT);
    }

    /**
     * Schedules cleanup of a retired attempt.
     *
     * The short delay gives teleports and world changes time to
     * fully settle before we attempt to unload anything.
     */
    public synchronized boolean scheduleCleanup(
        WorldSet retiredWorldSet
    ) {
        Objects.requireNonNull(retiredWorldSet);

        if (shuttingDown || !plugin.isEnabled()) {
            plugin.getLogger().warning(
                "[Instigate Cafe Hardcore] "
                    + "Skipping cleanup for retired attempt #"
                    + retiredWorldSet.attemptNumber()
                    + " because the plugin is shutting down."
            );

            return false;
        }

        if (unloadInProgress || deletingAttempts.contains(retiredWorldSet.attemptNumber())) {
            plugin.getLogger().warning(
                "[Instigate Cafe Hardcore] "
                    + "World cleanup is already in progress."
            );

            return false;
        }

        unloadInProgress = true;

        plugin.getLogger().info(
            "[Instigate Cafe Hardcore] "
                + "Scheduled cleanup for retired attempt #"
                + retiredWorldSet.attemptNumber()
                + "."
        );

        try {
            pendingUnloadTask =
                plugin.getServer()
                    .getScheduler()
                    .runTaskLater(
                        plugin,
                        () -> attemptCleanup(
                            retiredWorldSet,
                            0
                        ),
                        INITIAL_DELAY_TICKS
                    );
        } catch (RuntimeException exception) {
            unloadInProgress = false;
            pendingUnloadTask = null;

            plugin.getLogger().log(
                Level.SEVERE,
                "[Instigate Cafe Hardcore] Failed to schedule cleanup for attempt #"
                    + retiredWorldSet.attemptNumber() + ".",
                exception
            );

            return false;
        }

        return true;
    }

    /**
     * Cancels any pending unload retries and waits briefly for in-flight
     * asynchronous deletion to complete during server shutdown.
     */
    public synchronized void shutdown() {
        shuttingDown = true;

        if (pendingUnloadTask != null) {
            pendingUnloadTask.cancel();
            pendingUnloadTask = null;
        }

        unloadInProgress = false;

        if (activeAsyncDeletions <= 0) {
            return;
        }

        plugin.getLogger().info(
            "[Instigate Cafe Hardcore] "
                + "Waiting up to "
                + (SHUTDOWN_WAIT_MILLIS / 1000L)
                + "s for asynchronous world cleanup of attempt(s) "
                + deletingAttempts
                + " to finish..."
        );

        long deadline = System.currentTimeMillis() + SHUTDOWN_WAIT_MILLIS;
        while (activeAsyncDeletions > 0) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0L) {
                break;
            }
            try {
                wait(remaining);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (activeAsyncDeletions > 0) {
            plugin.getLogger().warning(
                "[Instigate Cafe Hardcore] "
                    + "Shutdown interrupted asynchronous cleanup for retired attempt(s) "
                    + deletingAttempts
                    + ". Partial retired attempt folders may remain on disk for manual removal."
            );
        }
    }

    private void attemptCleanup(
        WorldSet retiredWorldSet,
        int retryCount
    ) {
        if (shuttingDown || !plugin.isEnabled()) {
            finishUnloadPhase();
            return;
        }

        /*
         * Paper warns against loading/unloading worlds while
         * worlds themselves are being ticked.
         */
        if (Bukkit.isTickingWorlds()) {
            retryLater(
                retiredWorldSet,
                retryCount,
                "server is currently ticking worlds"
            );

            return;
        }

        /*
         * Never unload a world while a player is still inside it.
         */
        if (containsPlayers(retiredWorldSet)) {
            retryLater(
                retiredWorldSet,
                retryCount,
                "players are still inside the retired WorldSet"
            );

            return;
        }

        try {
            cleanupNow(
                retiredWorldSet
            );
        } catch (Exception exception) {
            plugin.getLogger().log(
                Level.SEVERE,
                "[Instigate Cafe Hardcore] "
                    + "Failed to clean retired attempt #"
                    + retiredWorldSet.attemptNumber()
                    + ".",
                exception
            );

            finishUnloadPhase();
        }
    }

    private synchronized void retryLater(
        WorldSet retiredWorldSet,
        int retryCount,
        String reason
    ) {
        if (shuttingDown || !plugin.isEnabled()) {
            finishUnloadPhase();
            return;
        }

        if (retryCount >= MAX_RETRIES) {
            plugin.getLogger().severe(
                "[Instigate Cafe Hardcore] "
                    + "Abandoning cleanup of attempt #"
                    + retiredWorldSet.attemptNumber()
                    + " because "
                    + reason
                    + "."
            );

            finishUnloadPhase();
            return;
        }

        try {
            pendingUnloadTask =
                plugin.getServer()
                    .getScheduler()
                    .runTaskLater(
                        plugin,
                        () -> attemptCleanup(
                            retiredWorldSet,
                            retryCount + 1
                        ),
                        RETRY_DELAY_TICKS
                    );
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                Level.SEVERE,
                "[Instigate Cafe Hardcore] Failed to schedule cleanup retry for attempt #"
                    + retiredWorldSet.attemptNumber() + ".",
                exception
            );

            finishUnloadPhase();
        }
    }

    private void cleanupNow(
        WorldSet retiredWorldSet
    ) throws IOException {
        if (shuttingDown) {
            finishUnloadPhase();
            return;
        }

        if (worldSetManager.getRetiredWorldSet() != retiredWorldSet) {
            throw new IOException(
                "Refusing to clean WorldSet #"
                    + retiredWorldSet.attemptNumber()
                    + " because it is not the current RETIRED WorldSet."
            );
        }

        plugin.getLogger().info(
            "[Instigate Cafe Hardcore] "
                + "Cleaning retired attempt #"
                + retiredWorldSet.attemptNumber()
                + "..."
        );

        WorldDeletionGuard.ProtectionContext preUnloadContext =
            buildProtectionContext(true);

        /*
         * Verify identity and non-protected status BEFORE unloading anything.
         * Also capture exact disk paths and check deletion eligibility before
         * Paper releases the World objects.
         */
        List<CleanupTarget> targets =
            List.of(
                prepareTarget(
                    retiredWorldSet,
                    retiredWorldSet.overworld(),
                    "overworld",
                    World.Environment.NORMAL,
                    preUnloadContext
                ),
                prepareTarget(
                    retiredWorldSet,
                    retiredWorldSet.nether(),
                    "nether",
                    World.Environment.NETHER,
                    preUnloadContext
                ),
                prepareTarget(
                    retiredWorldSet,
                    retiredWorldSet.end(),
                    "end",
                    World.Environment.THE_END,
                    preUnloadContext
                )
            );

        /*
         * Secondary dimensions first, Overworld last.
         */
        boolean endUnloaded =
            unloadIfLoaded(
                retiredWorldSet.end()
            );

        boolean netherUnloaded =
            unloadIfLoaded(
                retiredWorldSet.nether()
            );

        boolean overworldUnloaded =
            unloadIfLoaded(
                retiredWorldSet.overworld()
            );

        if (
            !endUnloaded
                || !netherUnloaded
                || !overworldUnloaded
        ) {
            throw new IOException(
                "Paper refused to unload one or more worlds "
                    + "belonging to attempt #"
                    + retiredWorldSet.attemptNumber()
                    + "."
            );
        }

        /*
         * At this point Paper no longer owns these worlds.
         */
        worldSetManager.clearRetiredWorldSet(
            retiredWorldSet
        );

        plugin.getLogger().info(
            "[Instigate Cafe Hardcore] "
                + "Attempt #"
                + retiredWorldSet.attemptNumber()
                + " successfully unloaded."
        );

        /*
         * Re-capture protection context AFTER unloading so every still-loaded
         * world on the server is explicitly protected from deletion.
         */
        WorldDeletionGuard.ProtectionContext postUnloadContext =
            buildProtectionContext(false);

        List<CleanupTarget> approvedForDeletion = new ArrayList<>();
        for (CleanupTarget target : targets) {
            if (!target.eligibleForDeletion()) {
                continue;
            }
            try {
                Path verifiedFolder = WorldDeletionGuard.verifyFolderForDeletion(
                    target.folder(),
                    target.identity(),
                    postUnloadContext,
                    false
                );
                approvedForDeletion.add(
                    new CleanupTarget(
                        target.dimension(),
                        target.world(),
                        verifiedFolder,
                        target.identity(),
                        true
                    )
                );
            } catch (IOException refusal) {
                plugin.getLogger().warning(
                    "[Instigate Cafe Hardcore] "
                        + "Refusing to delete retired world folder "
                        + target.folder()
                        + ": "
                        + refusal.getMessage()
                );
            }
        }

        if (approvedForDeletion.isEmpty()) {
            plugin.getLogger().warning(
                "[Instigate Cafe Hardcore] "
                    + "Attempt #"
                    + retiredWorldSet.attemptNumber()
                    + " was unloaded, but no world folders passed deletion safeguards."
            );
            finishUnloadPhase();
            return;
        }

        scheduleAsyncDeletion(
            retiredWorldSet.attemptNumber(),
            List.copyOf(approvedForDeletion),
            postUnloadContext
        );
    }

    private CleanupTarget prepareTarget(
        WorldSet retiredWorldSet,
        World world,
        String dimension,
        World.Environment expectedEnvironment,
        WorldDeletionGuard.ProtectionContext preUnloadContext
    ) throws IOException {
        Objects.requireNonNull(world, "world");

        if (world.equals(worldSetManager.getLobbyWorld())) {
            throw new IOException("Refusing to unload or delete the permanent lobby world.");
        }
        if (worldSetManager.isActiveWorld(world)) {
            throw new IOException("Refusing to unload or delete an ACTIVE world: " + world.getKey());
        }
        if (worldSetManager.isStandbyWorld(world)) {
            throw new IOException("Refusing to unload or delete a STANDBY world: " + world.getKey());
        }
        if ("minecraft".equalsIgnoreCase(world.getKey().getNamespace())) {
            throw new IOException("Refusing to unload or delete a vanilla Minecraft world: " + world.getKey());
        }
        if (world.getEnvironment() != expectedEnvironment) {
            throw new IOException(
                "World environment mismatch for " + world.getKey()
                    + ": expected " + expectedEnvironment
                    + " but found " + world.getEnvironment() + "."
            );
        }
        if (world.getSeed() != retiredWorldSet.seed()) {
            throw new IOException(
                "World seed mismatch for " + world.getKey() + "."
            );
        }

        WorldDeletionGuard.ExpectedWorldIdentity identity;
        try {
            identity = new WorldDeletionGuard.ExpectedWorldIdentity(
                pluginNamespace,
                retiredWorldSet.attemptNumber(),
                dimension,
                retiredWorldSet.seed(),
                world.getUID(),
                world.getKey().asString()
            );
        } catch (IllegalArgumentException exception) {
            throw new IOException(
                "Refusing to unload or delete unauthorized world: " + world.getKey(),
                exception
            );
        }

        Path rawFolder = world.getWorldFolder().toPath();

        // Check full deletion eligibility now. If the world is a legitimate
        // retired attempt world whose folder lacks an ownership marker or
        // contains a symlink, preserve the folder on disk rather than wedging
        // world rotation.
        try {
            Path verified = WorldDeletionGuard.verifyFolderForDeletion(
                rawFolder,
                identity,
                preUnloadContext,
                true
            );
            return new CleanupTarget(dimension, world, verified, identity, true);
        } catch (IOException refusal) {
            plugin.getLogger().warning(
                "[Instigate Cafe Hardcore] "
                    + "Preserving retired world folder on disk for "
                    + world.getKey()
                    + ": "
                    + refusal.getMessage()
            );
            return new CleanupTarget(dimension, world, rawFolder, identity, false);
        }
    }

    private WorldDeletionGuard.ProtectionContext buildProtectionContext(
        boolean preUnload
    ) throws IOException {
        World lobbyWorld = worldSetManager.getLobbyWorld();
        if (lobbyWorld == null) {
            throw new IOException("Lobby world is unavailable; refusing world deletion.");
        }
        Path lobbyFolder = lobbyWorld.getWorldFolder().toPath();

        Set<Path> activeFolders = collectWorldSetFolders(worldSetManager.getActiveWorldSet());
        Set<Path> standbyFolders = collectWorldSetFolders(worldSetManager.getStandbyWorldSet());

        Set<Path> permanentFolders = new HashSet<>();
        permanentFolders.add(lobbyFolder);

        Set<Path> loadedWorldFolders = new HashSet<>();
        Set<String> loadedWorldKeys = new HashSet<>();
        Set<UUID> loadedWorldUids = new HashSet<>();

        for (World loaded : Bukkit.getWorlds()) {
            Path folder = loaded.getWorldFolder().toPath();
            loadedWorldFolders.add(folder);
            loadedWorldKeys.add(loaded.getKey().asString().toLowerCase(Locale.ROOT));
            loadedWorldUids.add(loaded.getUID());

            if (!pluginNamespace.equalsIgnoreCase(loaded.getKey().getNamespace())) {
                permanentFolders.add(folder);
            }
        }

        // Also verify persisted state so an out-of-sync runtime reference
        // cannot authorize deletion of the persisted ACTIVE or STANDBY attempt.
        Integer persistedActiveAttempt = null;
        Integer persistedStandbyAttempt = null;
        WorldStateStore stateStore = worldSetManager.getStateStore();
        if (stateStore != null && stateStore.exists()) {
            WorldRotationState persisted = stateStore.load();
            persistedActiveAttempt = persisted.activeAttempt();
            persistedStandbyAttempt = persisted.standbyAttempt();
        } else if (!preUnload) {
            throw new IOException(
                "Persisted world-state.properties is unavailable; refusing world deletion."
            );
        }

        return new WorldDeletionGuard.ProtectionContext(
            worldContainer,
            pluginDataFolder,
            lobbyFolder,
            activeFolders,
            standbyFolders,
            permanentFolders,
            loadedWorldFolders,
            loadedWorldKeys,
            loadedWorldUids,
            persistedActiveAttempt,
            persistedStandbyAttempt
        );
    }

    private static Set<Path> collectWorldSetFolders(WorldSet worldSet) {
        if (worldSet == null) {
            return Set.of();
        }
        return Set.of(
            worldSet.overworld().getWorldFolder().toPath(),
            worldSet.nether().getWorldFolder().toPath(),
            worldSet.end().getWorldFolder().toPath()
        );
    }

    private synchronized void scheduleAsyncDeletion(
        int attemptNumber,
        List<CleanupTarget> targets,
        WorldDeletionGuard.ProtectionContext context
    ) {
        unloadInProgress = false;
        pendingUnloadTask = null;

        if (shuttingDown || !plugin.isEnabled()) {
            plugin.getLogger().warning(
                "[Instigate Cafe Hardcore] "
                    + "Shutdown interrupted before deleting folders for retired attempt #"
                    + attemptNumber
                    + ". Retired world folders were left intact on disk."
            );
            return;
        }

        activeAsyncDeletions++;
        deletingAttempts.add(attemptNumber);

        try {
            plugin.getServer()
                .getScheduler()
                .runTaskAsynchronously(
                    plugin,
                    () -> deleteWorldFolders(
                        attemptNumber,
                        targets,
                        context
                    )
                );
        } catch (RuntimeException exception) {
            finishAsyncDeletion(attemptNumber);
            plugin.getLogger().log(
                Level.SEVERE,
                "[Instigate Cafe Hardcore] Failed to schedule asynchronous folder deletion for attempt #"
                    + attemptNumber + ".",
                exception
            );
        }
    }

    private boolean unloadIfLoaded(
        World world
    ) {
        /*
         * Re-fetch the current World instance.
         *
         * This also makes retries safe if one dimension was already
         * successfully unloaded during an earlier attempt.
         */
        World loaded =
            Bukkit.getWorld(
                world.getKey()
            );

        if (loaded == null) {
            return true;
        }

        if (!loaded.getPlayers().isEmpty()) {
            return false;
        }

        /*
         * The world is disposable, so saving before deletion is
         * unnecessary.
         */
        return Bukkit.unloadWorld(
            loaded,
            false
        );
    }

    private boolean containsPlayers(
        WorldSet worldSet
    ) {
        return hasPlayers(
            worldSet.overworld()
        )
            || hasPlayers(
                worldSet.nether()
            )
            || hasPlayers(
                worldSet.end()
            );
    }

    private boolean hasPlayers(
        World world
    ) {
        World loaded =
            Bukkit.getWorld(
                world.getKey()
            );

        return loaded != null
            && !loaded.getPlayers().isEmpty();
    }

    private void deleteWorldFolders(
        int attemptNumber,
        List<CleanupTarget> targets,
        WorldDeletionGuard.ProtectionContext context
    ) {
        try {
            int deletedCount = 0;
            for (CleanupTarget target : targets) {
                if (shuttingDown || Thread.currentThread().isInterrupted()) {
                    plugin.getLogger().warning(
                        "[Instigate Cafe Hardcore] "
                            + "Interrupted filesystem cleanup for attempt #"
                            + attemptNumber
                            + " before deleting "
                            + target.folder()
                            + "."
                    );
                    break;
                }

                try {
                    WorldDeletionGuard.deleteVerifiedWorldFolder(
                        target.folder(),
                        target.identity(),
                        context
                    );

                    deletedCount++;

                    plugin.getLogger().info(
                        "[Instigate Cafe Hardcore] "
                            + "Deleted retired world folder: "
                            + target.folder()
                    );
                } catch (IOException exception) {
                    plugin.getLogger().log(
                        Level.SEVERE,
                        "[Instigate Cafe Hardcore] "
                            + "Failed to delete retired world folder: "
                            + target.folder(),
                        exception
                    );
                }
            }

            plugin.getLogger().info(
                "[Instigate Cafe Hardcore] "
                    + "Filesystem cleanup completed for attempt #"
                    + attemptNumber
                    + " (" + deletedCount + "/" + targets.size() + " folder(s) removed)."
            );
        } finally {
            finishAsyncDeletion(attemptNumber);
        }
    }

    private synchronized void finishUnloadPhase() {
        unloadInProgress = false;
        pendingUnloadTask = null;
    }

    private synchronized void finishAsyncDeletion(int attemptNumber) {
        activeAsyncDeletions = Math.max(0, activeAsyncDeletions - 1);
        deletingAttempts.remove(attemptNumber);
        notifyAll();
    }

    public synchronized boolean isCleanupInProgress() {
        return unloadInProgress || activeAsyncDeletions > 0;
    }
}
