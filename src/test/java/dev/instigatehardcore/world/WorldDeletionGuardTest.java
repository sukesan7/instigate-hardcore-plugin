package dev.instigatehardcore.world;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorldDeletionGuardTest {

    @TempDir
    Path tempRoot;

    private Path worldContainer;
    private Path pluginDataFolder;
    private Path lobbyRoot;
    private Path lobbyDimension;
    private Path vanillaNether;
    private Path vanillaEnd;

    @BeforeEach
    void setUp() throws IOException {
        worldContainer = Files.createDirectories(tempRoot.resolve("server"));
        pluginDataFolder = Files.createDirectories(worldContainer.resolve("plugins/InstigateHardcore"));
        lobbyRoot = Files.createDirectories(worldContainer.resolve("world"));
        Files.writeString(lobbyRoot.resolve("level.dat"), "lobby-level-marker");
        lobbyDimension = Files.createDirectories(lobbyRoot.resolve("dimensions/minecraft/overworld"));
        Files.writeString(lobbyDimension.resolve("marker.txt"), "keep-overworld");
        vanillaNether = Files.createDirectories(lobbyRoot.resolve("dimensions/minecraft/the_nether"));
        Files.writeString(vanillaNether.resolve("marker.txt"), "keep-nether");
        vanillaEnd = Files.createDirectories(lobbyRoot.resolve("dimensions/minecraft/the_end"));
        Files.writeString(vanillaEnd.resolve("marker.txt"), "keep-end");
    }

    @Test
    void deletesVerifiedPaper26NestedAttemptWorldAndPreservesLobbyAndSiblings() throws IOException {
        WorldDeletionGuard.ExpectedWorldIdentity retiredId = identity(12, "overworld", 12345L);
        WorldDeletionGuard.ExpectedWorldIdentity activeId = identity(13, "overworld", 67890L);
        WorldDeletionGuard.ExpectedWorldIdentity standbyId = identity(14, "overworld", 99999L);

        Path retiredDir = createNestedWorldDir(retiredId);
        Path activeDir = createNestedWorldDir(activeId);
        Path standbyDir = createNestedWorldDir(standbyId);

        assertEquals(
            WorldDeletionGuard.MarkerStatus.CREATED,
            WorldDeletionGuard.ensureOwnershipMarker(
                worldContainer, pluginDataFolder, lobbyRoot, retiredDir, retiredId, false, false
            )
        );
        assertEquals(
            WorldDeletionGuard.MarkerStatus.CREATED,
            WorldDeletionGuard.ensureOwnershipMarker(
                worldContainer, pluginDataFolder, lobbyRoot, activeDir, activeId, false, false
            )
        );

        WorldDeletionGuard.ProtectionContext context = context(
            lobbyRoot,
            Set.of(activeDir),
            Set.of(standbyDir),
            Set.of(lobbyRoot, lobbyDimension, vanillaNether, vanillaEnd),
            Set.of(lobbyRoot, activeDir, standbyDir),
            Set.of(activeId.worldKey(), standbyId.worldKey()),
            Set.of(activeId.worldUid(), standbyId.worldUid()),
            13,
            14
        );

        WorldDeletionGuard.deleteVerifiedWorldFolder(retiredDir, retiredId, context);

        assertFalse(Files.exists(retiredDir), "Verified retired attempt folder should be removed");
        assertTrue(Files.exists(lobbyRoot.resolve("level.dat")), "Lobby root must remain untouched");
        assertTrue(Files.exists(lobbyDimension.resolve("marker.txt")), "Vanilla overworld must remain untouched");
        assertTrue(Files.exists(activeDir.resolve("region/r.0.0.mca")), "Active world must remain untouched");
        assertTrue(Files.exists(standbyDir.resolve("region/r.0.0.mca")), "Standby world must remain untouched");
    }

    @Test
    void deletesVerifiedFlatLayoutAttemptWorld() throws IOException {
        WorldDeletionGuard.ExpectedWorldIdentity retiredId = identity(5, "nether", 555L);
        Path retiredDir = Files.createDirectories(worldContainer.resolve("attempt_000005_nether"));
        Files.createDirectories(retiredDir.resolve("region"));
        Files.writeString(retiredDir.resolve("region/r.0.0.mca"), "data");

        assertEquals(
            WorldDeletionGuard.MarkerStatus.CREATED,
            WorldDeletionGuard.ensureOwnershipMarker(
                worldContainer, pluginDataFolder, lobbyRoot, retiredDir, retiredId, false, false
            )
        );

        WorldDeletionGuard.ProtectionContext context = baseContext(lobbyRoot, 6, 7);
        WorldDeletionGuard.deleteVerifiedWorldFolder(retiredDir, retiredId, context);

        assertFalse(Files.exists(retiredDir));
        assertTrue(Files.exists(lobbyRoot.resolve("level.dat")));
    }

    @Test
    void refusesDeletingProtectedContainerLobbyVanillaAndPluginDataDirectories() throws IOException {
        WorldDeletionGuard.ExpectedWorldIdentity id = identity(12, "overworld", 12345L);
        WorldDeletionGuard.ProtectionContext context = baseContext(lobbyRoot, 13, 14);

        // Stamp fake markers even on protected dirs to prove layout/protection checks block independently.
        WorldOwnershipMarker forged = WorldOwnershipMarker.create(id, 1000L, false);
        WorldOwnershipMarker.write(lobbyRoot, forged);
        WorldOwnershipMarker.write(lobbyDimension, forged);
        WorldOwnershipMarker.write(vanillaNether, forged);
        WorldOwnershipMarker.write(pluginDataFolder, forged);

        Path dimensionsRoot = lobbyRoot.resolve("dimensions");
        Path namespaceRoot = Files.createDirectories(dimensionsRoot.resolve("instigatehardcore"));
        WorldOwnershipMarker.write(dimensionsRoot, forged);
        WorldOwnershipMarker.write(namespaceRoot, forged);

        assertThrows(IOException.class,
            () -> WorldDeletionGuard.deleteVerifiedWorldFolder(worldContainer, id, context));
        assertThrows(IOException.class,
            () -> WorldDeletionGuard.deleteVerifiedWorldFolder(lobbyRoot, id, context));
        assertThrows(IOException.class,
            () -> WorldDeletionGuard.deleteVerifiedWorldFolder(lobbyDimension, id, context));
        assertThrows(IOException.class,
            () -> WorldDeletionGuard.deleteVerifiedWorldFolder(vanillaNether, id, context));
        assertThrows(IOException.class,
            () -> WorldDeletionGuard.deleteVerifiedWorldFolder(pluginDataFolder, id, context));
        assertThrows(IOException.class,
            () -> WorldDeletionGuard.deleteVerifiedWorldFolder(dimensionsRoot, id, context));
        assertThrows(IOException.class,
            () -> WorldDeletionGuard.deleteVerifiedWorldFolder(namespaceRoot, id, context));

        assertTrue(Files.exists(lobbyRoot.resolve("level.dat")));
        assertTrue(Files.exists(lobbyDimension.resolve("marker.txt")));
        assertTrue(Files.exists(vanillaNether.resolve("marker.txt")));
        assertTrue(Files.exists(pluginDataFolder));
    }

    @Test
    void refusesDeletingPathOutsideWorldContainerAndTraversalPaths() throws IOException {
        Path externalDir = Files.createDirectories(tempRoot.resolve("external/attempt_000012_overworld"));
        Files.writeString(externalDir.resolve("precious.txt"), "do-not-delete");

        WorldDeletionGuard.ExpectedWorldIdentity id = identity(12, "overworld", 12345L);
        WorldOwnershipMarker.write(externalDir, WorldOwnershipMarker.create(id, 1000L, false));

        WorldDeletionGuard.ProtectionContext context = baseContext(lobbyRoot, 13, 14);

        assertThrows(IOException.class,
            () -> WorldDeletionGuard.deleteVerifiedWorldFolder(externalDir, id, context));

        Path traversalPath = worldContainer.resolve("../external/attempt_000012_overworld");
        assertThrows(IOException.class,
            () -> WorldDeletionGuard.deleteVerifiedWorldFolder(traversalPath, id, context));

        assertTrue(Files.exists(externalDir.resolve("precious.txt")));
    }

    @Test
    void refusesDeletingActiveStandbyPersistedAndLoadedWorlds() throws IOException {
        WorldDeletionGuard.ExpectedWorldIdentity activeId = identity(13, "overworld", 1300L);
        WorldDeletionGuard.ExpectedWorldIdentity standbyId = identity(14, "overworld", 1400L);
        WorldDeletionGuard.ExpectedWorldIdentity loadedId = identity(12, "overworld", 1200L);

        Path activeDir = createNestedWorldDir(activeId);
        Path standbyDir = createNestedWorldDir(standbyId);
        Path loadedDir = createNestedWorldDir(loadedId);

        WorldOwnershipMarker.write(activeDir, WorldOwnershipMarker.create(activeId, 1000L, false));
        WorldOwnershipMarker.write(standbyDir, WorldOwnershipMarker.create(standbyId, 1000L, false));
        WorldOwnershipMarker.write(loadedDir, WorldOwnershipMarker.create(loadedId, 1000L, false));

        // 1. Active world folder
        assertThrows(IOException.class, () -> WorldDeletionGuard.deleteVerifiedWorldFolder(
            activeDir, activeId,
            context(lobbyRoot, Set.of(activeDir), Set.of(standbyDir), Set.of(lobbyRoot),
                Set.of(), Set.of(), Set.of(), 99, 100)
        ));

        // 2. Standby world folder
        assertThrows(IOException.class, () -> WorldDeletionGuard.deleteVerifiedWorldFolder(
            standbyDir, standbyId,
            context(lobbyRoot, Set.of(activeDir), Set.of(standbyDir), Set.of(lobbyRoot),
                Set.of(), Set.of(), Set.of(), 99, 100)
        ));

        // 3. Persisted active/standby attempt numbers even without folder sets
        assertThrows(IOException.class, () -> WorldDeletionGuard.deleteVerifiedWorldFolder(
            activeDir, activeId,
            context(lobbyRoot, Set.of(), Set.of(), Set.of(lobbyRoot),
                Set.of(), Set.of(), Set.of(), 13, 14)
        ));
        assertThrows(IOException.class, () -> WorldDeletionGuard.deleteVerifiedWorldFolder(
            standbyDir, standbyId,
            context(lobbyRoot, Set.of(), Set.of(), Set.of(lobbyRoot),
                Set.of(), Set.of(), Set.of(), 13, 14)
        ));

        // 4. Still loaded by key, UID, or folder
        assertThrows(IOException.class, () -> WorldDeletionGuard.deleteVerifiedWorldFolder(
            loadedDir, loadedId,
            context(lobbyRoot, Set.of(), Set.of(), Set.of(lobbyRoot),
                Set.of(), Set.of(loadedId.worldKey()), Set.of(), 13, 14)
        ));
        assertThrows(IOException.class, () -> WorldDeletionGuard.deleteVerifiedWorldFolder(
            loadedDir, loadedId,
            context(lobbyRoot, Set.of(), Set.of(), Set.of(lobbyRoot),
                Set.of(), Set.of(), Set.of(loadedId.worldUid()), 13, 14)
        ));
        assertThrows(IOException.class, () -> WorldDeletionGuard.deleteVerifiedWorldFolder(
            loadedDir, loadedId,
            context(lobbyRoot, Set.of(), Set.of(), Set.of(lobbyRoot),
                Set.of(loadedDir), Set.of(), Set.of(), 13, 14)
        ));

        assertTrue(Files.exists(activeDir.resolve("region/r.0.0.mca")));
        assertTrue(Files.exists(standbyDir.resolve("region/r.0.0.mca")));
        assertTrue(Files.exists(loadedDir.resolve("region/r.0.0.mca")));
    }

    @Test
    void refusesDeletingUnmarkedOrMismatchedOwnershipFolders() throws IOException {
        WorldDeletionGuard.ExpectedWorldIdentity id = identity(12, "overworld", 12345L);
        Path unmarkedDir = createNestedWorldDir(id);
        WorldDeletionGuard.ProtectionContext context = baseContext(lobbyRoot, 13, 14);

        // Missing marker -> refused
        assertThrows(IOException.class,
            () -> WorldDeletionGuard.deleteVerifiedWorldFolder(unmarkedDir, id, context));
        assertTrue(Files.exists(unmarkedDir.resolve("region/r.0.0.mca")));

        // Wrong seed in marker -> refused
        WorldDeletionGuard.ExpectedWorldIdentity wrongSeed = identity(12, "overworld", 99999L);
        WorldOwnershipMarker.write(unmarkedDir, WorldOwnershipMarker.create(wrongSeed, 1000L, false));
        assertThrows(IOException.class,
            () -> WorldDeletionGuard.deleteVerifiedWorldFolder(unmarkedDir, id, context));
        assertTrue(Files.exists(unmarkedDir.resolve("region/r.0.0.mca")));

        // Corrupt marker -> refused
        Files.writeString(unmarkedDir.resolve(WorldOwnershipMarker.FILE_NAME), "schema=99\n");
        assertThrows(IOException.class,
            () -> WorldDeletionGuard.deleteVerifiedWorldFolder(unmarkedDir, id, context));
        assertTrue(Files.exists(unmarkedDir.resolve("region/r.0.0.mca")));
    }

    @Test
    void refusesSymbolicLinkTargetIntermediateAncestorMarkerAndNestedSymlink() throws IOException {
        Path protectedExternal = Files.createDirectories(tempRoot.resolve("protected_target"));
        Path secretFile = Files.writeString(protectedExternal.resolve("important.dat"), "never-delete");

        WorldDeletionGuard.ExpectedWorldIdentity id = identity(20, "overworld", 2020L);
        WorldDeletionGuard.ProtectionContext context = baseContext(lobbyRoot, 21, 22);

        // 1. Target world directory itself is a symlink to protectedExternal
        Path nsDir = Files.createDirectories(lobbyRoot.resolve("dimensions/instigatehardcore"));
        Path symlinkWorld = nsDir.resolve("attempt_000020_overworld");
        Files.createSymbolicLink(symlinkWorld, protectedExternal);
        WorldOwnershipMarker.write(protectedExternal, WorldOwnershipMarker.create(id, 1000L, false));

        assertThrows(IOException.class,
            () -> WorldDeletionGuard.deleteVerifiedWorldFolder(symlinkWorld, id, context));
        assertTrue(Files.isSymbolicLink(symlinkWorld));
        assertTrue(Files.exists(secretFile));
        Files.delete(symlinkWorld);

        // 2. Intermediate ancestor directory is a symlink escaping container
        Path externalNs = Files.createDirectories(tempRoot.resolve("external_ns"));
        Path externalAttempt = Files.createDirectories(externalNs.resolve("attempt_000020_overworld"));
        Path externalFile = Files.writeString(externalAttempt.resolve("keep.dat"), "safe");
        WorldOwnershipMarker.write(externalAttempt, WorldOwnershipMarker.create(id, 1000L, false));

        Path altLevel = Files.createDirectories(worldContainer.resolve("altworld/dimensions"));
        Files.createSymbolicLink(altLevel.resolve("instigatehardcore"), externalNs);
        Path viaSymlinkAncestor = altLevel.resolve("instigatehardcore/attempt_000020_overworld");

        assertThrows(IOException.class,
            () -> WorldDeletionGuard.deleteVerifiedWorldFolder(viaSymlinkAncestor, id, context));
        assertTrue(Files.exists(externalFile));

        // 3. Ownership marker itself is a symlink
        Path realWorld = createNestedWorldDir(id);
        Path externalMarker = tempRoot.resolve("external_marker.properties");
        WorldOwnershipMarker.write(protectedExternal, WorldOwnershipMarker.create(id, 1000L, false));
        Files.copy(protectedExternal.resolve(WorldOwnershipMarker.FILE_NAME), externalMarker);
        Files.createSymbolicLink(realWorld.resolve(WorldOwnershipMarker.FILE_NAME), externalMarker);

        assertThrows(IOException.class,
            () -> WorldDeletionGuard.deleteVerifiedWorldFolder(realWorld, id, context));
        assertTrue(Files.exists(realWorld.resolve("region/r.0.0.mca")));
        Files.delete(realWorld.resolve(WorldOwnershipMarker.FILE_NAME));

        // 4. Real world with valid marker containing a nested symlink pointing to protectedExternal
        WorldOwnershipMarker.write(realWorld, WorldOwnershipMarker.create(id, 1000L, false));
        Files.createSymbolicLink(realWorld.resolve("region/escape_link"), protectedExternal);

        assertThrows(IOException.class,
            () -> WorldDeletionGuard.deleteVerifiedWorldFolder(realWorld, id, context));
        assertTrue(Files.exists(secretFile), "Protected symlink target must remain intact");
        assertTrue(Files.exists(realWorld.resolve("region/r.0.0.mca")),
            "Pre-scan must abort before deleting any files when a nested symlink is present");
    }

    @Test
    void legacyMigrationClaimsOnlyEligibleExistingWorldsAndRefusesUnrelatedOrConflictingWorlds()
        throws IOException {
        WorldDeletionGuard.ExpectedWorldIdentity id = identity(93, "overworld", -7043558448546615933L);
        Path preexistingDir = createNestedWorldDir(id);

        // Fresh install (not eligible for legacy migration) must NOT claim pre-existing unmarked folder.
        assertEquals(
            WorldDeletionGuard.MarkerStatus.REFUSED_PREEXISTING_UNCLAIMED,
            WorldDeletionGuard.ensureOwnershipMarker(
                worldContainer, pluginDataFolder, lobbyRoot, preexistingDir, id, true, false
            )
        );
        assertFalse(Files.exists(preexistingDir.resolve(WorldOwnershipMarker.FILE_NAME)));

        // Existing installation with matching persisted state safely migrates the marker.
        assertEquals(
            WorldDeletionGuard.MarkerStatus.MIGRATED,
            WorldDeletionGuard.ensureOwnershipMarker(
                worldContainer, pluginDataFolder, lobbyRoot, preexistingDir, id, true, true
            )
        );
        WorldOwnershipMarker migrated = WorldOwnershipMarker.read(preexistingDir).orElseThrow();
        assertTrue(migrated.migrated());
        assertTrue(migrated.matches(id));

        // Subsequent startup verifies the marker without rewriting.
        assertEquals(
            WorldDeletionGuard.MarkerStatus.VERIFIED,
            WorldDeletionGuard.ensureOwnershipMarker(
                worldContainer, pluginDataFolder, lobbyRoot, preexistingDir, id, true, false
            )
        );

        // Mismatched identity on an already-marked folder must refuse and preserve existing marker.
        WorldDeletionGuard.ExpectedWorldIdentity differentSeed = identity(93, "overworld", 42L);
        assertEquals(
            WorldDeletionGuard.MarkerStatus.REFUSED_MISMATCH,
            WorldDeletionGuard.ensureOwnershipMarker(
                worldContainer, pluginDataFolder, lobbyRoot, preexistingDir, differentSeed, true, true
            )
        );
        assertEquals(id.seed(), WorldOwnershipMarker.read(preexistingDir).orElseThrow().seed());

        // Migrated world can then be safely deleted once retired and unloaded.
        WorldDeletionGuard.ProtectionContext context = baseContext(lobbyRoot, 94, 95);
        WorldDeletionGuard.deleteVerifiedWorldFolder(preexistingDir, id, context);
        assertFalse(Files.exists(preexistingDir));
    }

    @Test
    void supportsPaper26LobbyDimensionAsLobbyFolder() throws IOException {
        WorldDeletionGuard.ExpectedWorldIdentity retiredId = identity(8, "end", 808L);
        Path retiredDir = createNestedWorldDir(retiredId);
        WorldOwnershipMarker.write(retiredDir, WorldOwnershipMarker.create(retiredId, 1000L, false));

        // Simulate Paper returning world/dimensions/minecraft/overworld as lobbyWorld.getWorldFolder()
        WorldDeletionGuard.ProtectionContext context = baseContext(lobbyDimension, 9, 10);

        // Lobby root ("world") must still be protected even when lobbyFolder is the nested dimension.
        assertThrows(IOException.class,
            () -> WorldDeletionGuard.deleteVerifiedWorldFolder(lobbyRoot, retiredId, context));
        assertTrue(Files.exists(lobbyRoot.resolve("level.dat")));

        // Legitimate retired attempt dimension under world/dimensions/instigatehardcore is deleted cleanly.
        WorldDeletionGuard.deleteVerifiedWorldFolder(retiredDir, retiredId, context);
        assertFalse(Files.exists(retiredDir));
        assertTrue(Files.exists(lobbyDimension.resolve("marker.txt")));
    }

    private Path createNestedWorldDir(WorldDeletionGuard.ExpectedWorldIdentity id) throws IOException {
        Path dir = Files.createDirectories(
            lobbyRoot.resolve("dimensions")
                .resolve(id.namespace())
                .resolve(id.expectedKeyPath())
        );
        Files.createDirectories(dir.resolve("region"));
        Files.writeString(dir.resolve("region/r.0.0.mca"), "chunk-data");
        return dir;
    }

    private static WorldDeletionGuard.ExpectedWorldIdentity identity(
        int attempt,
        String dimension,
        long seed
    ) {
        String key = WorldDeletionGuard.DEFAULT_NAMESPACE + ":"
            + WorldDeletionGuard.ExpectedWorldIdentity.expectedKeyPath(attempt, dimension);
        return new WorldDeletionGuard.ExpectedWorldIdentity(
            WorldDeletionGuard.DEFAULT_NAMESPACE,
            attempt,
            dimension,
            seed,
            UUID.randomUUID(),
            key
        );
    }

    private WorldDeletionGuard.ProtectionContext baseContext(
        Path lobbyFolder,
        int persistedActive,
        int persistedStandby
    ) {
        return context(
            lobbyFolder,
            Set.of(),
            Set.of(),
            Set.of(lobbyRoot, lobbyDimension, vanillaNether, vanillaEnd),
            Set.of(lobbyFolder),
            Set.of("minecraft:overworld"),
            Set.of(),
            persistedActive,
            persistedStandby
        );
    }

    private WorldDeletionGuard.ProtectionContext context(
        Path lobbyFolder,
        Set<Path> activeFolders,
        Set<Path> standbyFolders,
        Set<Path> permanentFolders,
        Set<Path> loadedFolders,
        Set<String> loadedKeys,
        Set<UUID> loadedUids,
        Integer persistedActive,
        Integer persistedStandby
    ) {
        return new WorldDeletionGuard.ProtectionContext(
            worldContainer,
            pluginDataFolder,
            lobbyFolder,
            activeFolders,
            standbyFolders,
            permanentFolders,
            loadedFolders,
            loadedKeys,
            loadedUids,
            persistedActive,
            persistedStandby
        );
    }
}
