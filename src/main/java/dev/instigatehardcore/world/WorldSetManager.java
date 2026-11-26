package dev.instigatehardcore.world;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class WorldSetManager {

    private static final long STANDBY_START_TIME =
        1000L;

    private final JavaPlugin plugin;
    private final WorldStateStore stateStore;
    private final String lobbyWorldName;
    private final int preloadRadiusChunks;
    private final String pluginNamespace;

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

        this.pluginNamespace =
            plugin.getName().toLowerCase(Locale.ROOT);
    }

    /**
     * Loads or creates the permanent lobby, ACTIVE attempt
     * and STANDBY attempt.
     *
     * Persistent recovery must already have completed before
     * this method is called.
     */
    public synchronized void initialize(
        int currentAttempt
    ) throws IOException {
        initialize(currentAttempt, Map.of());
    }

    /**
     * Loads or creates the permanent lobby, ACTIVE attempt, and STANDBY attempt.
     *
     * @param preexistingAttemptSeeds attempt-to-seed pairs that were actually
     *                                loaded from a pre-existing world-state.properties
     *                                prior to startup; only matching worlds are
     *                                eligible for legacy ownership-marker migration.
     */
    public synchronized void initialize(
        int currentAttempt,
        Map<Integer, Long> preexistingAttemptSeeds
    ) throws IOException {

        Map<Integer, Long> migrationProvenance =
            preexistingAttemptSeeds == null
                ? Map.of()
                : Map.copyOf(preexistingAttemptSeeds);

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

        if (
            pluginNamespace.equalsIgnoreCase(
                lobbyWorld.getKey().getNamespace()
            )
        ) {
            throw new IOException(
                "Configured worlds.lobby-world points to a managed attempt world ("
                    + lobbyWorld.getKey()
                    + "). Configure a permanent non-attempt world as lobby."
            );
        }

        WorldRotationState state =
            stateStore.loadOrCreate(
                currentAttempt
            );

        /*
         * WorldRecoveryManager must resolve interrupted
         * transactions before actual campaign worlds are loaded.
         */
        if (
            state.phase()
                != WorldRotationPhase.STABLE
        ) {
            throw new IOException(
                "World pipeline is still marked "
                    + state.phase()
                    + ". Startup recovery must run before "
                    + "WorldSetManager initialization."
            );
        }

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
                state.activeSeed(),
                isEligibleForLegacyMigration(
                    migrationProvenance,
                    state.activeAttempt(),
                    state.activeSeed()
                )
            );

        /*
         * ACTIVE worlds recovered during a normal restart must keep
         * their existing time/weather, but their gameplay cycles
         * must be running.
         */
        prepareActiveWorldSetAfterStartup(
            activeWorldSet
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
                state.standbySeed(),
                isEligibleForLegacyMigration(
                    migrationProvenance,
                    state.standbyAttempt(),
                    state.standbySeed()
                )
            );

        prepareStandbyWorldSet(
            standbyWorldSet
        );

        retiredWorldSet =
            null;

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

        if (
            state.phase()
                != WorldRotationPhase.STABLE
        ) {
            throw new IOException(
                "Campaign state is not STABLE."
            );
        }
    }

    /**
     * Durably begins a world-rotation transaction.
     *
     * This must happen before any player is moved into the
     * standby attempt.
     *
     * Once ROTATING is persisted, recovery considers the old
     * attempt finished even if Paper crashes before promotion.
     */
    public synchronized void beginRotation()
        throws IOException {

        if (activeWorldSet == null) {
            throw new IOException(
                "Cannot begin rotation without an active WorldSet."
            );
        }

        if (standbyWorldSet == null) {
            throw new IOException(
                "Cannot begin rotation without a standby WorldSet."
            );
        }

        if (retiredWorldSet != null) {
            throw new IOException(
                "Cannot begin rotation while retired attempt #"
                    + retiredWorldSet.attemptNumber()
                    + " is still awaiting cleanup."
            );
        }

        WorldRotationState state =
            stateStore.load();

        if (
            state.phase()
                != WorldRotationPhase.STABLE
        ) {
            throw new IOException(
                "Cannot begin rotation while persistent state is "
                    + state.phase()
                    + "."
            );
        }

        validateLoadedWorldsAgainstState(
            state
        );

        WorldRotationState rotating =
            new WorldRotationState(
                state.activeAttempt(),
                state.activeSeed(),
                state.standbyAttempt(),
                state.standbySeed(),
                WorldRotationPhase.ROTATING
            );

        stateStore.save(
            rotating
        );

        plugin.getLogger().info(
            "[Instigate Cafe Hardcore] "
                + "World rotation transaction started: #"
                + state.activeAttempt()
                + " -> #"
                + state.standbyAttempt()
                + "."
        );
    }

    /**
     * Promotes STANDBY -> ACTIVE.
     *
     * ACTIVE -> RETIRED.
     *
     * This operation also commits a new STABLE persistent world
     * pipeline containing metadata for the next standby attempt.
     */
    public synchronized WorldSet promoteStandby()
        throws IOException {

        if (retiredWorldSet != null) {
            throw new IOException(
                "Cannot rotate while retired attempt #"
                    + retiredWorldSet.attemptNumber()
                    + " is still awaiting cleanup."
            );
        }

        if (standbyWorldSet == null) {
            throw new IOException(
                "Cannot rotate worlds because no standby WorldSet exists."
            );
        }

        if (activeWorldSet == null) {
            throw new IOException(
                "Cannot rotate worlds because no active WorldSet exists."
            );
        }

        WorldRotationState persistedState =
            stateStore.load();

        if (
            persistedState.phase()
                != WorldRotationPhase.ROTATING
        ) {
            throw new IOException(
                "Cannot promote standby because the world pipeline "
                    + "is not marked ROTATING."
            );
        }

        validateLoadedWorldsAgainstState(
            persistedState
        );

        WorldSet previousActive =
            activeWorldSet;

        WorldSet promoted =
            standbyWorldSet;

        if (promoted.attemptNumber() >= Integer.MAX_VALUE) {
            throw new IOException(
                "Attempt number overflow during standby promotion."
            );
        }

        int nextStandbyAttempt =
            promoted.attemptNumber()
                + 1;

        long nextStandbySeed =
            generateSeedDifferentFrom(
                promoted.seed()
            );

        /*
         * Promotion becomes durable here.
         *
         * If Paper crashes after this save but before stats are
         * advanced, startup recovery sees ACTIVE = stats + 1 and
         * reconciles StatsManager automatically.
         */
        WorldRotationState nextState =
            new WorldRotationState(
                promoted.attemptNumber(),
                promoted.seed(),
                nextStandbyAttempt,
                nextStandbySeed,
                WorldRotationPhase.STABLE
            );

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

        plugin.getLogger().info(
            "[Instigate Cafe Hardcore] "
                + "Attempt #"
                + retiredWorldSet.attemptNumber()
                + " marked as RETIRED."
        );

        return previousActive;
    }

    /**
     * Creates the replacement STANDBY WorldSet described by
     * the current STABLE world-state.properties.
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
            state.phase()
                != WorldRotationPhase.STABLE
        ) {
            throw new IOException(
                "Cannot generate replacement standby while world "
                    + "state is "
                    + state.phase()
                    + "."
            );
        }

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
                state.standbySeed(),
                false
            );

        prepareStandbyWorldSet(
            standbyWorldSet
        );

        plugin.getLogger().info(
            "[Instigate Cafe Hardcore] "
                + "Standby attempt #"
                + standbyWorldSet.attemptNumber()
                + " is ready."
        );

        return standbyWorldSet;
    }

    /**
     * Called by WorldCleanupManager after all three retired
     * dimensions have successfully unloaded.
     */
    public synchronized void clearRetiredWorldSet(
        WorldSet expected
    ) throws IOException {

        Objects.requireNonNull(
            expected
        );

        if (retiredWorldSet == null) {
            return;
        }

        if (retiredWorldSet != expected) {
            throw new IOException(
                "Attempted to clear an unexpected retired WorldSet. "
                    + "Expected attempt #"
                    + retiredWorldSet.attemptNumber()
                    + " but received #"
                    + expected.attemptNumber()
                    + "."
            );
        }

        plugin.getLogger().info(
            "[Instigate Cafe Hardcore] "
                + "Retired reference cleared for attempt #"
                + retiredWorldSet.attemptNumber()
                + "."
        );

        retiredWorldSet =
            null;
    }

    /*
     * ------------------------------------------------------------
     * STANDBY WORLD STATE
     * ------------------------------------------------------------
     */

    /**
     * Keeps a STANDBY attempt in a deterministic fresh-start state.
     *
     * Only the Overworld has a normal day/night and weather cycle,
     * so the Nether and End are intentionally left alone.
     */
    private void prepareStandbyWorldSet(
        WorldSet worldSet
    ) {
        Objects.requireNonNull(
            worldSet
        );

        World overworld =
            worldSet.overworld();

        /*
         * setTime() changes the relative time of day without
         * rewinding the world's absolute game time.
         */
        overworld.setTime(
            STANDBY_START_TIME
        );

        overworld.setStorm(
            false
        );

        overworld.setThundering(
            false
        );

        overworld.setGameRule(
            GameRules.ADVANCE_TIME,
            false
        );

        overworld.setGameRule(
            GameRules.ADVANCE_WEATHER,
            false
        );

        plugin.getLogger().info(
            "[Instigate Cafe Hardcore] "
                + "Standby attempt #"
                + worldSet.attemptNumber()
                + " frozen at morning with clear weather."
        );
    }

    /**
     * Restores normal Overworld cycles for the ACTIVE attempt during
     * plugin startup.
     *
     * Do not alter time or weather here. A normal Paper restart in
     * the middle of an attempt must preserve the attempt exactly as
     * it was.
     */
    private void prepareActiveWorldSetAfterStartup(
        WorldSet worldSet
    ) {
        Objects.requireNonNull(
            worldSet
        );

        World overworld =
            worldSet.overworld();

        overworld.setGameRule(
            GameRules.ADVANCE_TIME,
            true
        );

        overworld.setGameRule(
            GameRules.ADVANCE_WEATHER,
            true
        );
    }

    /**
     * Converts the current STANDBY attempt into its fresh ACTIVE
     * starting state immediately before players are transferred.
     *
     * The morning/clear state is asserted again in case another
     * plugin or an administrator modified the standby while it was
     * waiting.
     */
    public synchronized void prepareStandbyForActivation()
        throws IOException {

        if (standbyWorldSet == null) {
            throw new IOException(
                "Cannot prepare standby for activation because "
                    + "no standby WorldSet exists."
            );
        }

        World overworld =
            standbyWorldSet.overworld();

        overworld.setTime(
            STANDBY_START_TIME
        );

        overworld.setStorm(
            false
        );

        overworld.setThundering(
            false
        );

        /*
         * The world is about to become playable. Resume normal
         * progression before the first player is transferred.
         */
        overworld.setGameRule(
            GameRules.ADVANCE_TIME,
            true
        );

        overworld.setGameRule(
            GameRules.ADVANCE_WEATHER,
            true
        );

        plugin.getLogger().info(
            "[Instigate Cafe Hardcore] "
                + "Standby attempt #"
                + standbyWorldSet.attemptNumber()
                + " prepared for ACTIVE gameplay at morning."
        );
    }

    private void validateLoadedWorldsAgainstState(
        WorldRotationState state
    ) throws IOException {

        if (activeWorldSet == null) {
            throw new IOException(
                "No ACTIVE WorldSet is loaded."
            );
        }

        if (standbyWorldSet == null) {
            throw new IOException(
                "No STANDBY WorldSet is loaded."
            );
        }

        if (
            activeWorldSet.attemptNumber()
                != state.activeAttempt()
            || activeWorldSet.seed()
                != state.activeSeed()
        ) {
            throw new IOException(
                "Loaded ACTIVE WorldSet does not match persistent state."
            );
        }

        if (
            standbyWorldSet.attemptNumber()
                != state.standbyAttempt()
            || standbyWorldSet.seed()
                != state.standbySeed()
        ) {
            throw new IOException(
                "Loaded STANDBY WorldSet does not match persistent state."
            );
        }
    }

    private boolean isEligibleForLegacyMigration(
        Map<Integer, Long> migrationProvenance,
        int attemptNumber,
        long expectedSeed
    ) {
        Long persistedSeed = migrationProvenance.get(attemptNumber);
        return persistedSeed != null && persistedSeed.longValue() == expectedSeed;
    }

    private WorldSet createOrLoadWorldSet(
        int attemptNumber,
        long seed,
        boolean eligibleForLegacyMigration
    ) throws IOException {

        World overworld =
            createOrLoadWorld(
                attemptNumber,
                seed,
                "overworld",
                World.Environment.NORMAL,
                eligibleForLegacyMigration
            );

        World nether =
            createOrLoadWorld(
                attemptNumber,
                seed,
                "nether",
                World.Environment.NETHER,
                eligibleForLegacyMigration
            );

        World end =
            createOrLoadWorld(
                attemptNumber,
                seed,
                "end",
                World.Environment.THE_END,
                eligibleForLegacyMigration
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
        World.Environment environment,
        boolean eligibleForLegacyMigration
    ) throws IOException {

        NamespacedKey key =
            createWorldKey(
                attemptNumber,
                dimension
            );

        Path worldContainer =
            plugin.getServer()
                .getWorldContainer()
                .toPath();

        Path lobbyFolder =
            lobbyWorld.getWorldFolder().toPath();

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

            ensureWorldOwnershipMarker(
                worldContainer,
                lobbyFolder,
                existing,
                attemptNumber,
                dimension,
                seed,
                true,
                eligibleForLegacyMigration
            );

            configureWorld(
                existing
            );

            return existing;
        }

        Set<Path> preexistingCandidates =
            WorldDeletionGuard.findExistingCandidateFolders(
                worldContainer,
                lobbyFolder,
                pluginNamespace,
                attemptNumber,
                dimension
            );

        WorldCreator creator =
            WorldCreator.ofKey(
                key
            )
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

        Path worldFolder = world.getWorldFolder().toPath();
        boolean existedBeforeCreate =
            preexistingCandidates.contains(worldFolder.toAbsolutePath().normalize());
        if (!existedBeforeCreate) {
            try {
                existedBeforeCreate =
                    preexistingCandidates.contains(worldFolder.toRealPath());
            } catch (IOException ignored) {
                // Keep lexical check result; marker guard still checks canonical path.
            }
        }

        ensureWorldOwnershipMarker(
            worldContainer,
            lobbyFolder,
            world,
            attemptNumber,
            dimension,
            seed,
            existedBeforeCreate,
            eligibleForLegacyMigration
        );

        configureWorld(
            world
        );

        return world;
    }

    private void ensureWorldOwnershipMarker(
        Path worldContainer,
        Path lobbyFolder,
        World world,
        int attemptNumber,
        String dimension,
        long seed,
        boolean existedBeforeCreate,
        boolean eligibleForLegacyMigration
    ) {
        try {
            WorldDeletionGuard.ExpectedWorldIdentity identity =
                new WorldDeletionGuard.ExpectedWorldIdentity(
                    pluginNamespace,
                    attemptNumber,
                    dimension,
                    seed,
                    world.getUID(),
                    world.getKey().asString()
                );

            WorldDeletionGuard.MarkerStatus status =
                WorldDeletionGuard.ensureOwnershipMarker(
                    worldContainer,
                    plugin.getDataFolder().toPath(),
                    lobbyFolder,
                    world.getWorldFolder().toPath(),
                    identity,
                    existedBeforeCreate,
                    eligibleForLegacyMigration
                );

            switch (status) {
                case CREATED -> plugin.getLogger().fine(
                    "[Instigate Cafe Hardcore] Recorded ownership marker for "
                        + world.getKey() + "."
                );
                case VERIFIED -> plugin.getLogger().fine(
                    "[Instigate Cafe Hardcore] Verified ownership marker for "
                        + world.getKey() + "."
                );
                case MIGRATED -> plugin.getLogger().info(
                    "[Instigate Cafe Hardcore] Migrated ownership marker for existing managed world "
                        + world.getKey() + "."
                );
                case REFUSED_PREEXISTING_UNCLAIMED -> plugin.getLogger().warning(
                    "[Instigate Cafe Hardcore] Refused to claim pre-existing unmarked world directory for "
                        + world.getKey()
                        + ". Automatic cleanup will not delete this directory."
                );
                case REFUSED_MISMATCH -> plugin.getLogger().warning(
                    "[Instigate Cafe Hardcore] Existing ownership marker mismatch for "
                        + world.getKey()
                        + ". Leaving marker untouched; automatic cleanup will not delete this directory."
                );
            }
        } catch (IOException | IllegalArgumentException exception) {
            // Fail safely: never claim or overwrite an unsafe/unrelated directory.
            plugin.getLogger().warning(
                "[Instigate Cafe Hardcore] Could not verify or write ownership marker for "
                    + world.getKey()
                    + ": "
                    + exception.getMessage()
                    + " Automatic cleanup will refuse to delete this directory."
            );
        }
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

    public String getPluginNamespace() {
        return pluginNamespace;
    }

    public synchronized boolean hasStandbyWorldSet() {
        return standbyWorldSet != null;
    }

    public synchronized boolean hasRetiredWorldSet() {
        return retiredWorldSet != null;
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