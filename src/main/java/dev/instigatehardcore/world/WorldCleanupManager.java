package dev.instigatehardcore.world;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

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

    private final JavaPlugin plugin;
    private final WorldSetManager worldSetManager;
    private final Path worldContainer;

    private boolean cleanupInProgress;

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

        if (cleanupInProgress) {
            plugin.getLogger().warning(
                "[Instigate Cafe Hardcore] "
                    + "World cleanup is already in progress."
            );

            return false;
        }

        cleanupInProgress = true;

        plugin.getLogger().info(
            "[Instigate Cafe Hardcore] "
                + "Scheduled cleanup for retired attempt #"
                + retiredWorldSet.attemptNumber()
                + "."
        );

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

        return true;
    }

    private void attemptCleanup(
        WorldSet retiredWorldSet,
        int retryCount
    ) {
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
            plugin.getLogger().severe(
                "[Instigate Cafe Hardcore] "
                    + "Failed to clean retired attempt #"
                    + retiredWorldSet.attemptNumber()
                    + "."
            );

            exception.printStackTrace();

            finishCleanup();
        }
    }

    private void retryLater(
        WorldSet retiredWorldSet,
        int retryCount,
        String reason
    ) {
        if (retryCount >= MAX_RETRIES) {
            plugin.getLogger().severe(
                "[Instigate Cafe Hardcore] "
                    + "Abandoning cleanup of attempt #"
                    + retiredWorldSet.attemptNumber()
                    + " because "
                    + reason
                    + "."
            );

            finishCleanup();
            return;
        }

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
    }

    private void cleanupNow(
        WorldSet retiredWorldSet
    ) throws IOException {

        plugin.getLogger().info(
            "[Instigate Cafe Hardcore] "
                + "Cleaning retired attempt #"
                + retiredWorldSet.attemptNumber()
                + "..."
        );

        /*
         * Capture disk paths BEFORE unloading the worlds.
         *
         * After unloading, we should no longer rely on the World
         * objects for filesystem information.
         */
        List<Path> worldFolders =
            List.of(
                validateWorldFolder(
                    retiredWorldSet.overworld()
                ),
                validateWorldFolder(
                    retiredWorldSet.nether()
                ),
                validateWorldFolder(
                    retiredWorldSet.end()
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
         * Recursive deletion can become expensive after a long run,
         * so do filesystem I/O away from the server thread.
         */
        plugin.getServer()
            .getScheduler()
            .runTaskAsynchronously(
                plugin,
                () -> deleteWorldFolders(
                    retiredWorldSet.attemptNumber(),
                    worldFolders
                )
            );

        finishCleanup();
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

    /**
     * Ensures that a path we intend to delete actually belongs to
     * Paper's world container and is not one of our live worlds.
     */
    private Path validateWorldFolder(
        World world
    ) throws IOException {

        Path folder =
            world.getWorldFolder()
                .toPath()
                .toAbsolutePath()
                .normalize();

        if (!folder.startsWith(worldContainer)) {
            throw new IOException(
                "Refusing to delete world outside world container: "
                    + folder
            );
        }

        if (folder.equals(worldContainer)) {
            throw new IOException(
                "Refusing to delete the world container itself."
            );
        }

        Path lobbyFolder =
            worldSetManager
                .getLobbyWorld()
                .getWorldFolder()
                .toPath()
                .toAbsolutePath()
                .normalize();

        if (folder.equals(lobbyFolder)) {
            throw new IOException(
                "Refusing to delete the permanent lobby world."
            );
        }

        WorldSet active =
            worldSetManager
                .getActiveWorldSet();

        if (active != null) {
            if (
                folder.equals(
                    active.overworld()
                        .getWorldFolder()
                        .toPath()
                        .toAbsolutePath()
                        .normalize()
                )
                    || folder.equals(
                        active.nether()
                            .getWorldFolder()
                            .toPath()
                            .toAbsolutePath()
                            .normalize()
                    )
                    || folder.equals(
                        active.end()
                            .getWorldFolder()
                            .toPath()
                            .toAbsolutePath()
                            .normalize()
                    )
            ) {
                throw new IOException(
                    "Refusing to delete an ACTIVE world: "
                        + folder
                );
            }
        }

        WorldSet standby =
            worldSetManager
                .getStandbyWorldSet();

        if (standby != null) {
            if (
                folder.equals(
                    standby.overworld()
                        .getWorldFolder()
                        .toPath()
                        .toAbsolutePath()
                        .normalize()
                )
                    || folder.equals(
                        standby.nether()
                            .getWorldFolder()
                            .toPath()
                            .toAbsolutePath()
                            .normalize()
                    )
                    || folder.equals(
                        standby.end()
                            .getWorldFolder()
                            .toPath()
                            .toAbsolutePath()
                            .normalize()
                    )
            ) {
                throw new IOException(
                    "Refusing to delete a STANDBY world: "
                        + folder
                );
            }
        }

        return folder;
    }

    private void deleteWorldFolders(
        int attemptNumber,
        List<Path> worldFolders
    ) {
        for (Path folder : worldFolders) {
            try {
                deleteRecursively(
                    folder
                );

                plugin.getLogger().info(
                    "[Instigate Cafe Hardcore] "
                        + "Deleted retired world folder: "
                        + folder
                );
            } catch (IOException exception) {
                plugin.getLogger().severe(
                    "[Instigate Cafe Hardcore] "
                        + "Failed to delete retired world folder: "
                        + folder
                );

                exception.printStackTrace();
            }
        }

        plugin.getLogger().info(
            "[Instigate Cafe Hardcore] "
                + "Filesystem cleanup completed for attempt #"
                + attemptNumber
                + "."
        );
    }

    private void deleteRecursively(
        Path root
    ) throws IOException {

        if (!Files.exists(root)) {
            return;
        }

        try (
            Stream<Path> paths =
                Files.walk(root)
        ) {
            for (
                Path path :
                paths.sorted(
                    Comparator.reverseOrder()
                ).toList()
            ) {
                Files.deleteIfExists(
                    path
                );
            }
        }
    }

    private synchronized void finishCleanup() {
        cleanupInProgress =
            false;
    }

    public synchronized boolean isCleanupInProgress() {
        return cleanupInProgress;
    }
}