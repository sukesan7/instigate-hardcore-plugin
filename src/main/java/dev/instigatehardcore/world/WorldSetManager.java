package dev.instigatehardcore.world;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

public final class WorldSetManager {

    private final JavaPlugin plugin;
    private final WorldStateStore stateStore;
    private final String lobbyWorldName;
    private final int preloadRadiusChunks;

    private World lobbyWorld;

    private WorldSet activeWorldSet;
    private WorldSet standbyWorldSet;
    private WorldSet retiredWorldSet;

    public WorldSetManager(
        JavaPlugin plugin,
        WorldStateStore stateStore,
        String lobbyWorldName,
        int preloadRadiusChunks
    ) {
        this.plugin =
            Objects.requireNonNull(plugin);

        this.stateStore =
            Objects.requireNonNull(stateStore);

        this.lobbyWorldName =
            Objects.requireNonNull(lobbyWorldName);

        if (preloadRadiusChunks < 0) {
            throw new IllegalArgumentException(
                "Preload radius cannot be negative."
            );
        }

        this.preloadRadiusChunks =
            preloadRadiusChunks;
    }

    /**
     * Initializes the permanent lobby, ACTIVE attempt,
     * and STANDBY attempt.
     */
    public synchronized void initialize(
        int currentAttempt
    ) throws IOException {

        lobbyWorld =
            Bukkit.getWorld(
                lobbyWorldName
            );

        if (lobbyWorld == null) {
            throw new IOException(
                "Lobby world is not loaded: "
                    + lobbyWorldName
            );
        }

        WorldRotationState state =
            stateStore.loadOrCreate(
                currentAttempt
            );

        validateCampaignState(
            state,
            currentAttempt
        );

        plugin.getLogger().info(
            "[Instigate Cafe Hardcore] "
                + "Preparing active attempt #"
                + state.activeAttempt()
                + "..."
        );

        activeWorldSet =
            createOrLoadWorldSet(
                state.activeAttempt(),
                state.activeSeed()
            );

        plugin.getLogger().info(
            "[Instigate Cafe Hardcore] "
                + "Preparing standby attempt #"
                + state.standbyAttempt()
                + "..."
        );

        standbyWorldSet =
            createOrLoadWorldSet(
                state.standbyAttempt(),
                state.standbySeed()
            );

        retiredWorldSet = null;

        logWorldState();
    }

    private void validateCampaignState(
        WorldRotationState state,
        int currentAttempt
    ) throws IOException {

        if (
            state.activeAttempt()
                != currentAttempt
        ) {
            throw new IOException(
                "World state and campaign statistics disagree. "
                    + "Stats attempt is #"
                    + currentAttempt
                    + " but active world attempt is #"
                    + state.activeAttempt()
                    + "."
            );
        }

        if (
            state.standbyAttempt()
                != currentAttempt + 1
        ) {
            throw new IOException(
                "Invalid standby attempt #"
                    + state.standbyAttempt()
                    + ". Expected #"
                    + (currentAttempt + 1)
                    + "."
            );
        }
    }

    /**
     * Promotes the currently prepared STANDBY WorldSet
     * to ACTIVE.
     *
     * The old active WorldSet becomes RETIRED.
     *
     * The identity and seed of the next standby attempt are
     * persisted immediately, although its actual worlds are
     * generated afterward.
     *
     * @return the previously active WorldSet
     */
    public synchronized WorldSet promoteStandby()
        throws IOException {

        if (standbyWorldSet == null) {
            throw new IOException(
                "Cannot rotate worlds because no standby WorldSet exists."
            );
        }

        WorldSet previousActive =
            activeWorldSet;

        WorldSet promoted =
            standbyWorldSet;

        int nextStandbyAttempt =
            promoted.attemptNumber() + 1;

        long nextStandbySeed =
            generateSeedDifferentFrom(
                promoted.seed()
            );

        WorldRotationState nextState =
            new WorldRotationState(
                promoted.attemptNumber(),
                promoted.seed(),
                nextStandbyAttempt,
                nextStandbySeed
            );

        /*
         * Persist the pipeline before changing our
         * in-memory references.
         */
        stateStore.save(
            nextState
        );

        retiredWorldSet =
            previousActive;

        activeWorldSet =
            promoted;

        standbyWorldSet =
            null;

        plugin.getLogger().info(
            "[Instigate Cafe Hardcore] "
                + "Attempt #"
                + activeWorldSet.attemptNumber()
                + " promoted to ACTIVE."
        );

        return previousActive;
    }

    /**
     * Generates the next standby WorldSet using the identity
     * already persisted in world-state.properties.
     */
    public synchronized WorldSet createReplacementStandby()
        throws IOException {

        if (standbyWorldSet != null) {
            return standbyWorldSet;
        }

        if (activeWorldSet == null) {
            throw new IOException(
                "Cannot create standby WorldSet without an active WorldSet."
            );
        }

        WorldRotationState state =
            stateStore.load();

        if (
            state.activeAttempt()
                != activeWorldSet.attemptNumber()
        ) {
            throw new IOException(
                "Persisted active attempt does not match "
                    + "the in-memory active WorldSet."
            );
        }

        if (
            state.activeSeed()
                != activeWorldSet.seed()
        ) {
            throw new IOException(
                "Persisted active seed does not match "
                    + "the in-memory active WorldSet."
            );
        }

        plugin.getLogger().info(
            "[Instigate Cafe Hardcore] "
                + "Generating standby attempt #"
                + state.standbyAttempt()
                + "..."
        );

        standbyWorldSet =
            createOrLoadWorldSet(
                state.standbyAttempt(),
                state.standbySeed()
            );

        plugin.getLogger().info(
            "[Instigate Cafe Hardcore] "
                + "Standby attempt #"
                + standbyWorldSet.attemptNumber()
                + " is ready."
        );

        return standbyWorldSet;
    }

    private WorldSet createOrLoadWorldSet(
        int attemptNumber,
        long seed
    ) throws IOException {

        World overworld =
            createOrLoadWorld(
                attemptNumber,
                seed,
                "overworld",
                World.Environment.NORMAL
            );

        World nether =
            createOrLoadWorld(
                attemptNumber,
                seed,
                "nether",
                World.Environment.NETHER
            );

        World end =
            createOrLoadWorld(
                attemptNumber,
                seed,
                "end",
                World.Environment.THE_END
            );

        prepareSpawnArea(
            overworld
        );

        prepareCentralChunk(
            nether
        );

        prepareCentralChunk(
            end
        );

        return new WorldSet(
            attemptNumber,
            seed,
            overworld,
            nether,
            end
        );
    }

    private World createOrLoadWorld(
        int attemptNumber,
        long seed,
        String dimension,
        World.Environment environment
    ) throws IOException {

        NamespacedKey key =
            createWorldKey(
                attemptNumber,
                dimension
            );

        World existing =
            Bukkit.getWorld(
                key
            );

        if (existing != null) {
            validateExistingWorld(
                existing,
                seed,
                environment
            );

            configureWorld(
                existing
            );

            return existing;
        }

        WorldCreator creator =
            WorldCreator.ofKey(key)
                .seed(seed)
                .environment(environment)
                .generateStructures(true)
                .hardcore(true);

        World world =
            creator.createWorld();

        if (world == null) {
            throw new IOException(
                "Paper failed to create world "
                    + key
                    + "."
            );
        }

        validateExistingWorld(
            world,
            seed,
            environment
        );

        configureWorld(
            world
        );

        return world;
    }

    private NamespacedKey createWorldKey(
        int attemptNumber,
        String dimension
    ) {
        String key =
            String.format(
                "attempt_%06d_%s",
                attemptNumber,
                dimension
            );

        return new NamespacedKey(
            plugin,
            key
        );
    }

    private void validateExistingWorld(
        World world,
        long expectedSeed,
        World.Environment expectedEnvironment
    ) throws IOException {

        if (
            world.getSeed()
                != expectedSeed
        ) {
            throw new IOException(
                "World seed mismatch for "
                    + world.getKey()
                    + ". Expected "
                    + expectedSeed
                    + " but found "
                    + world.getSeed()
                    + "."
            );
        }

        if (
            world.getEnvironment()
                != expectedEnvironment
        ) {
            throw new IOException(
                "World environment mismatch for "
                    + world.getKey()
                    + ". Expected "
                    + expectedEnvironment
                    + " but found "
                    + world.getEnvironment()
                    + "."
            );
        }
    }

    private void configureWorld(
        World world
    ) {
        world.setHardcore(
            true
        );

        world.setDifficulty(
            Difficulty.HARD
        );

        world.setAutoSave(
            true
        );
    }

    /**
     * Generates a small area surrounding Overworld spawn.
     */
    private void prepareSpawnArea(
        World world
    ) {
        Location spawn =
            world.getSpawnLocation();

        int centerChunkX =
            spawn.getBlockX() >> 4;

        int centerChunkZ =
            spawn.getBlockZ() >> 4;

        for (
            int x =
                centerChunkX
                    - preloadRadiusChunks;
            x <=
                centerChunkX
                    + preloadRadiusChunks;
            x++
        ) {
            for (
                int z =
                    centerChunkZ
                        - preloadRadiusChunks;
                z <=
                    centerChunkZ
                        + preloadRadiusChunks;
                z++
            ) {
                world.getChunkAt(
                    x,
                    z,
                    true
                );
            }
        }
    }

    private void prepareCentralChunk(
        World world
    ) {
        Location spawn =
            world.getSpawnLocation();

        world.getChunkAt(
            spawn.getBlockX() >> 4,
            spawn.getBlockZ() >> 4,
            true
        );
    }

    private long generateSeedDifferentFrom(
        long activeSeed
    ) {
        long seed;

        do {
            seed =
                ThreadLocalRandom
                    .current()
                    .nextLong();
        } while (
            seed == activeSeed
        );

        return seed;
    }

    private void logWorldState() {
        plugin.getLogger().info(
            "[Instigate Cafe Hardcore] "
                + "World pipeline ready."
        );

        plugin.getLogger().info(
            "Lobby: "
                + lobbyWorld.getKey()
        );

        logWorldSet(
            "ACTIVE",
            activeWorldSet
        );

        logWorldSet(
            "STANDBY",
            standbyWorldSet
        );
    }

    private void logWorldSet(
        String role,
        WorldSet worldSet
    ) {
        plugin.getLogger().info(
            role
                + " attempt #"
                + worldSet.attemptNumber()
                + " | seed "
                + worldSet.seed()
        );

        plugin.getLogger().info(
            "  Overworld: "
                + worldSet
                    .overworld()
                    .getKey()
        );

        plugin.getLogger().info(
            "  Nether: "
                + worldSet
                    .nether()
                    .getKey()
        );

        plugin.getLogger().info(
            "  End: "
                + worldSet
                    .end()
                    .getKey()
        );
    }

    public synchronized World getLobbyWorld() {
        return lobbyWorld;
    }

    public synchronized WorldSet getActiveWorldSet() {
        return activeWorldSet;
    }

    public synchronized WorldSet getStandbyWorldSet() {
        return standbyWorldSet;
    }

    public synchronized WorldSet getRetiredWorldSet() {
        return retiredWorldSet;
    }

    public synchronized WorldStateStore getStateStore() {
        return stateStore;
    }

    public synchronized boolean hasStandbyWorldSet() {
        return standbyWorldSet != null;
    }

    public synchronized boolean isActiveWorld(
        World world
    ) {
        return activeWorldSet != null
            && activeWorldSet.contains(
                world
            );
    }

    public synchronized boolean isStandbyWorld(
        World world
    ) {
        return standbyWorldSet != null
            && standbyWorldSet.contains(
                world
            );
    }

    public synchronized boolean isRetiredWorld(
        World world
    ) {
        return retiredWorldSet != null
            && retiredWorldSet.contains(
                world
            );
    }
}