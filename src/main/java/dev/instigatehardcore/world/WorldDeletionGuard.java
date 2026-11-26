package dev.instigatehardcore.world;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Pure filesystem and identity safety guard for attempt world ownership and
 * deletion.
 *
 * Enforces:
 * - canonical containment inside Paper's world container;
 * - rejection of symbolic links on target, ancestor path segments, marker file,
 *   and any nested entry within a world directory;
 * - legitimate Paper world-folder layouts (Paper 26.3+ namespaced dimension
 *   trees and classic flat world-container layouts);
 * - generated-world identity and ownership marker verification;
 * - explicit rejection of permanent, lobby, vanilla, active, standby, loaded,
 *   plugin-data, and ancestor directories.
 */
public final class WorldDeletionGuard {

    public static final String DEFAULT_NAMESPACE = "instigatehardcore";

    private static final Set<String> VALID_DIMENSIONS =
        Set.of("overworld", "nether", "end");

    private static final Set<String> RESERVED_ROOT_DIRECTORIES =
        Set.of(
            "plugins",
            "logs",
            "cache",
            "libraries",
            "versions",
            "config",
            "crash-reports",
            "bundler",
            ".paper"
        );

    private WorldDeletionGuard() {
    }

    public record ExpectedWorldIdentity(
        String namespace,
        int attemptNumber,
        String dimension,
        long seed,
        UUID worldUid,
        String worldKey
    ) {
        public ExpectedWorldIdentity {
            Objects.requireNonNull(namespace, "namespace");
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(worldUid, "worldUid");
            Objects.requireNonNull(worldKey, "worldKey");

            namespace = namespace.toLowerCase(Locale.ROOT);
            dimension = dimension.toLowerCase(Locale.ROOT);
            worldKey = worldKey.toLowerCase(Locale.ROOT);

            if (namespace.isBlank() || "minecraft".equals(namespace)) {
                throw new IllegalArgumentException(
                    "Managed world namespace is invalid: " + namespace
                );
            }
            if (attemptNumber < 1) {
                throw new IllegalArgumentException("Attempt number must be positive.");
            }
            if (!VALID_DIMENSIONS.contains(dimension)) {
                throw new IllegalArgumentException("Invalid attempt dimension: " + dimension);
            }

            String expectedKey = namespace + ":" + expectedKeyPath(attemptNumber, dimension);
            if (!expectedKey.equals(worldKey)) {
                throw new IllegalArgumentException(
                    "World key mismatch. Expected " + expectedKey + " but found " + worldKey + "."
                );
            }
        }

        public String expectedKeyPath() {
            return expectedKeyPath(attemptNumber, dimension);
        }

        public static String expectedKeyPath(int attemptNumber, String dimension) {
            return String.format(
                Locale.ROOT,
                "attempt_%06d_%s",
                attemptNumber,
                dimension.toLowerCase(Locale.ROOT)
            );
        }
    }

    public record ProtectionContext(
        Path worldContainer,
        Path pluginDataFolder,
        Path lobbyFolder,
        Set<Path> activeFolders,
        Set<Path> standbyFolders,
        Set<Path> permanentFolders,
        Set<Path> loadedWorldFolders,
        Set<String> loadedWorldKeys,
        Set<UUID> loadedWorldUids,
        Integer persistedActiveAttempt,
        Integer persistedStandbyAttempt
    ) {
        public ProtectionContext {
            Objects.requireNonNull(worldContainer, "worldContainer");
            Objects.requireNonNull(lobbyFolder, "lobbyFolder");
            activeFolders = activeFolders == null ? Set.of() : Set.copyOf(activeFolders);
            standbyFolders = standbyFolders == null ? Set.of() : Set.copyOf(standbyFolders);
            permanentFolders = permanentFolders == null ? Set.of() : Set.copyOf(permanentFolders);
            loadedWorldFolders = loadedWorldFolders == null ? Set.of() : Set.copyOf(loadedWorldFolders);
            loadedWorldKeys = loadedWorldKeys == null ? Set.of() : Set.copyOf(loadedWorldKeys);
            loadedWorldUids = loadedWorldUids == null ? Set.of() : Set.copyOf(loadedWorldUids);
        }
    }

    public enum MarkerStatus {
        CREATED,
        VERIFIED,
        MIGRATED,
        REFUSED_PREEXISTING_UNCLAIMED,
        REFUSED_MISMATCH
    }

    /**
     * Resolves and verifies that {@code candidate} is a real directory strictly
     * contained within the canonical {@code worldContainer} with no symbolic
     * links at the target or along any path segment inside the container.
     */
    public static Path resolveContainedDirectory(
        Path worldContainer,
        Path candidate
    ) throws IOException {
        Objects.requireNonNull(worldContainer, "worldContainer");
        Objects.requireNonNull(candidate, "candidate");

        if (!Files.exists(worldContainer) || !Files.isDirectory(worldContainer)) {
            throw new IOException("World container does not exist or is not a directory: " + worldContainer);
        }

        Path normalizedContainer = worldContainer.toAbsolutePath().normalize();
        Path canonicalContainer = worldContainer.toRealPath();
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();

        if (!Files.exists(normalizedCandidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World folder does not exist: " + normalizedCandidate);
        }
        if (Files.isSymbolicLink(normalizedCandidate)) {
            throw new IOException("Refusing symbolic-link world folder: " + normalizedCandidate);
        }
        if (!Files.isDirectory(normalizedCandidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World path is not a directory: " + normalizedCandidate);
        }

        // Check every segment from the container down to the candidate for symlinks.
        Path relativeFromContainer;
        if (normalizedCandidate.startsWith(canonicalContainer)) {
            relativeFromContainer = canonicalContainer.relativize(normalizedCandidate);
        } else if (normalizedCandidate.startsWith(normalizedContainer)) {
            relativeFromContainer = normalizedContainer.relativize(normalizedCandidate);
        } else {
            throw new IOException("Refusing world path outside world container: " + normalizedCandidate);
        }

        if (relativeFromContainer.toString().isEmpty()) {
            throw new IOException("Refusing to operate on the world container itself.");
        }

        Path walk = canonicalContainer;
        for (Path segment : relativeFromContainer) {
            walk = walk.resolve(segment);
            if (Files.isSymbolicLink(walk)) {
                throw new IOException(
                    "Refusing path traversing symbolic link inside world container: " + walk
                );
            }
        }

        Path canonicalCandidate = normalizedCandidate.toRealPath();
        if (!canonicalCandidate.startsWith(canonicalContainer)) {
            throw new IOException(
                "Refusing world whose canonical path escapes world container: " + canonicalCandidate
            );
        }
        if (canonicalCandidate.equals(canonicalContainer)) {
            throw new IOException("Refusing to operate on the world container itself.");
        }

        Path expectedCanonical = canonicalContainer.resolve(relativeFromContainer).normalize();
        if (!canonicalCandidate.equals(expectedCanonical)) {
            throw new IOException(
                "Refusing world whose canonical path differs from contained path: " + canonicalCandidate
            );
        }

        return canonicalCandidate;
    }

    /**
     * Verifies that {@code canonicalFolder} matches a legitimate Paper layout
     * for {@code expected}.
     *
     * Supported layouts:
     * 1. Paper 26.3+ namespaced dimensions:
     *    <worldContainer>/<level-dir>/dimensions/<namespace>/attempt_<NNNNNN>_<dim>
     * 2. Classic flat Bukkit/Paper layout:
     *    <worldContainer>/attempt_<NNNNNN>_<dim>
     *    <worldContainer>/<namespace>_attempt_<NNNNNN>_<dim>
     */
    public static boolean isLegitimatePaperWorldLayout(
        Path canonicalContainer,
        Path canonicalFolder,
        ExpectedWorldIdentity expected
    ) {
        Objects.requireNonNull(canonicalContainer, "canonicalContainer");
        Objects.requireNonNull(canonicalFolder, "canonicalFolder");
        Objects.requireNonNull(expected, "expected");

        if (!canonicalFolder.startsWith(canonicalContainer)
            || canonicalFolder.equals(canonicalContainer)) {
            return false;
        }

        Path rel = canonicalContainer.relativize(canonicalFolder);
        String expectedKeyPath = expected.expectedKeyPath();
        String expectedNamespace = expected.namespace();

        if (rel.getNameCount() == 1) {
            String dirName = rel.getName(0).toString();
            return dirName.equals(expectedKeyPath)
                || dirName.equals(expectedNamespace + "_" + expectedKeyPath);
        }

        if (rel.getNameCount() == 4) {
            String levelDir = rel.getName(0).toString();
            String dimensionsDir = rel.getName(1).toString();
            String namespaceDir = rel.getName(2).toString();
            String worldDir = rel.getName(3).toString();

            if (levelDir.isBlank()
                || ".".equals(levelDir)
                || "..".equals(levelDir)
                || RESERVED_ROOT_DIRECTORIES.contains(levelDir.toLowerCase(Locale.ROOT))) {
                return false;
            }

            return "dimensions".equals(dimensionsDir)
                && expectedNamespace.equals(namespaceDir)
                && expectedKeyPath.equals(worldDir);
        }

        return false;
    }

    /**
     * Collects existing filesystem paths (without following symlinks) where
     * Paper could place a world for {@code namespace}:{@code expectedKeyPath}.
     * Call before {@code WorldCreator.createWorld()} to determine whether the
     * directory already existed prior to creation.
     */
    public static Set<Path> findExistingCandidateFolders(
        Path worldContainer,
        Path lobbyFolder,
        String namespace,
        int attemptNumber,
        String dimension
    ) {
        Set<Path> found = new HashSet<>();
        if (worldContainer == null || namespace == null || dimension == null || attemptNumber < 1) {
            return found;
        }

        Path canonicalContainer;
        try {
            canonicalContainer = worldContainer.toRealPath();
        } catch (IOException exception) {
            canonicalContainer = worldContainer.toAbsolutePath().normalize();
        }

        String ns = namespace.toLowerCase(Locale.ROOT);
        String keyPath = ExpectedWorldIdentity.expectedKeyPath(attemptNumber, dimension);

        recordIfExists(found, canonicalContainer.resolve(keyPath));
        recordIfExists(found, canonicalContainer.resolve(ns + "_" + keyPath));

        if (lobbyFolder != null) {
            Path lobbyNorm = lobbyFolder.toAbsolutePath().normalize();
            recordIfExists(found, lobbyNorm.resolve("dimensions").resolve(ns).resolve(keyPath));

            // If Paper 26.3 returns <level>/dimensions/minecraft/overworld as lobbyFolder,
            // also check the parent level root <level>/dimensions/<ns>/<keyPath>.
            Path levelRoot = resolveLevelRootFromDimensionFolder(canonicalContainer, lobbyNorm);
            if (levelRoot != null) {
                recordIfExists(found, levelRoot.resolve("dimensions").resolve(ns).resolve(keyPath));
            }
        }

        // Also scan top-level directories under worldContainer for <level>/dimensions/<ns>/<keyPath>.
        try (var stream = Files.list(canonicalContainer)) {
            for (Path child : stream.toList()) {
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    recordIfExists(found, child.resolve("dimensions").resolve(ns).resolve(keyPath));
                }
            }
        } catch (IOException ignored) {
            // Best-effort pre-check; caller still validates post-creation.
        }

        return Set.copyOf(found);
    }

    /**
     * Establishes or verifies the ownership marker for a managed attempt world.
     *
     * Rules:
     * - Target must pass canonical containment, symlink, layout, and protected
     *   path checks.
     * - Existing valid matching marker -> VERIFIED.
     * - Existing mismatched or corrupt marker -> REFUSED_MISMATCH (never overwritten).
     * - No marker & freshly created -> writes marker and returns CREATED.
     * - No marker & pre-existing & explicitly eligible for legacy migration ->
     *   writes migrated marker and returns MIGRATED.
     * - No marker & pre-existing & NOT eligible -> REFUSED_PREEXISTING_UNCLAIMED
     *   (never claims unrelated worlds).
     */
    public static MarkerStatus ensureOwnershipMarker(
        Path worldContainer,
        Path pluginDataFolder,
        Path lobbyFolder,
        Path worldFolder,
        ExpectedWorldIdentity expected,
        boolean existedBeforeCreate,
        boolean eligibleForLegacyMigration
    ) throws IOException {
        Objects.requireNonNull(expected, "expected");

        Path canonicalContainer = worldContainer.toRealPath();
        Path canonicalFolder = resolveContainedDirectory(worldContainer, worldFolder);

        if (!isLegitimatePaperWorldLayout(canonicalContainer, canonicalFolder, expected)) {
            throw new IOException(
                "World folder does not match a legitimate managed Paper layout: " + canonicalFolder
            );
        }

        verifyNotLobbyOrDataFolder(canonicalContainer, pluginDataFolder, lobbyFolder, canonicalFolder);

        Optional<WorldOwnershipMarker> existing;
        try {
            existing = WorldOwnershipMarker.read(canonicalFolder);
        } catch (IOException corruptOrSymlinkMarker) {
            return MarkerStatus.REFUSED_MISMATCH;
        }

        if (existing.isPresent()) {
            return existing.get().matches(expected)
                ? MarkerStatus.VERIFIED
                : MarkerStatus.REFUSED_MISMATCH;
        }

        if (!existedBeforeCreate) {
            WorldOwnershipMarker marker = WorldOwnershipMarker.create(
                expected,
                System.currentTimeMillis(),
                false
            );
            WorldOwnershipMarker.write(canonicalFolder, marker);
            return MarkerStatus.CREATED;
        }

        if (eligibleForLegacyMigration) {
            WorldOwnershipMarker marker = WorldOwnershipMarker.create(
                expected,
                System.currentTimeMillis(),
                true
            );
            WorldOwnershipMarker.write(canonicalFolder, marker);
            return MarkerStatus.MIGRATED;
        }

        return MarkerStatus.REFUSED_PREEXISTING_UNCLAIMED;
    }

    /**
     * Validates that a world folder is strictly eligible for deletion.
     *
     * @param allowTargetLoaded true ONLY during pre-unload validation before
     *                          Paper unloads the retired world; false for
     *                          post-unload and pre-delete verification.
     * @return the canonical path verified safe for deletion.
     */
    public static Path verifyFolderForDeletion(
        Path candidateFolder,
        ExpectedWorldIdentity expected,
        ProtectionContext context,
        boolean allowTargetLoaded
    ) throws IOException {
        Objects.requireNonNull(candidateFolder, "candidateFolder");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(context, "context");

        Path canonicalContainer = context.worldContainer().toRealPath();
        Path canonicalFolder = resolveContainedDirectory(context.worldContainer(), candidateFolder);

        if (!isLegitimatePaperWorldLayout(canonicalContainer, canonicalFolder, expected)) {
            throw new IOException(
                "Refusing to delete folder outside legitimate managed Paper world layout: "
                    + canonicalFolder
            );
        }

        verifyNotLobbyOrDataFolder(
            canonicalContainer,
            context.pluginDataFolder(),
            context.lobbyFolder(),
            canonicalFolder
        );

        if (context.persistedActiveAttempt() != null
            && expected.attemptNumber() == context.persistedActiveAttempt()) {
            throw new IOException(
                "Refusing to delete persisted ACTIVE attempt #" + expected.attemptNumber() + "."
            );
        }

        if (context.persistedStandbyAttempt() != null
            && expected.attemptNumber() == context.persistedStandbyAttempt()) {
            throw new IOException(
                "Refusing to delete persisted STANDBY attempt #" + expected.attemptNumber() + "."
            );
        }

        verifyNotProtectedWorldSet(canonicalFolder, context.activeFolders(), "ACTIVE");
        verifyNotProtectedWorldSet(canonicalFolder, context.standbyFolders(), "STANDBY");
        verifyNotPermanentFolder(canonicalContainer, canonicalFolder, context.permanentFolders());

        if (!allowTargetLoaded) {
            if (context.loadedWorldKeys().contains(expected.worldKey())) {
                throw new IOException(
                    "Refusing to delete world that is still loaded by key: " + expected.worldKey()
                );
            }
            if (context.loadedWorldUids().contains(expected.worldUid())) {
                throw new IOException(
                    "Refusing to delete world that is still loaded by UID: " + expected.worldUid()
                );
            }
            verifyNotLoadedWorldFolder(canonicalContainer, canonicalFolder, context.loadedWorldFolders());
        } else {
            // Even before unloading the retired world itself, ensure no OTHER
            // protected/loaded folder is an ancestor or descendant conflict.
            verifyNoOtherLoadedConflict(canonicalContainer, canonicalFolder, context.loadedWorldFolders());
        }

        Optional<WorldOwnershipMarker> marker = WorldOwnershipMarker.read(canonicalFolder);
        if (marker.isEmpty()) {
            throw new IOException(
                "Refusing to delete unmanaged world folder without ownership marker: " + canonicalFolder
            );
        }
        if (!marker.get().matches(expected)) {
            throw new IOException(
                "Refusing to delete world folder with mismatched ownership marker: " + canonicalFolder
            );
        }

        verifyNoSymbolicLinksInsideTree(canonicalFolder);

        return canonicalFolder;
    }

    /**
     * Re-verifies all filesystem and ownership constraints and safely deletes
     * the retired world directory tree without ever following symbolic links.
     */
    public static void deleteVerifiedWorldFolder(
        Path candidateFolder,
        ExpectedWorldIdentity expected,
        ProtectionContext context
    ) throws IOException {
        Path canonicalFolder = verifyFolderForDeletion(candidateFolder, expected, context, false);

        Files.walkFileTree(
            canonicalFolder,
            EnumSet.noneOf(FileVisitOption.class),
            Integer.MAX_VALUE,
            new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(
                    Path dir,
                    BasicFileAttributes attrs
                ) throws IOException {
                    if (attrs.isSymbolicLink() || Files.isSymbolicLink(dir)) {
                        throw new IOException(
                            "Aborting deletion: encountered symbolic link directory " + dir
                        );
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attrs
                ) throws IOException {
                    if (attrs.isSymbolicLink() || Files.isSymbolicLink(file)) {
                        throw new IOException(
                            "Aborting deletion: encountered symbolic link file " + file
                        );
                    }
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(
                    Path dir,
                    IOException exc
                ) throws IOException {
                    if (exc != null) {
                        throw exc;
                    }
                    if (Files.isSymbolicLink(dir)) {
                        throw new IOException(
                            "Aborting deletion: encountered symbolic link directory " + dir
                        );
                    }
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            }
        );
    }

    private static void verifyNoSymbolicLinksInsideTree(Path canonicalFolder) throws IOException {
        Files.walkFileTree(
            canonicalFolder,
            EnumSet.noneOf(FileVisitOption.class),
            Integer.MAX_VALUE,
            new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(
                    Path dir,
                    BasicFileAttributes attrs
                ) throws IOException {
                    if (attrs.isSymbolicLink() || Files.isSymbolicLink(dir)) {
                        throw new IOException(
                            "Refusing to delete world folder containing symbolic link: " + dir
                        );
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attrs
                ) throws IOException {
                    if (attrs.isSymbolicLink() || Files.isSymbolicLink(file)) {
                        throw new IOException(
                            "Refusing to delete world folder containing symbolic link: " + file
                        );
                    }
                    return FileVisitResult.CONTINUE;
                }
            }
        );
    }

    private static void verifyNotLobbyOrDataFolder(
        Path canonicalContainer,
        Path pluginDataFolder,
        Path lobbyFolder,
        Path canonicalCandidate
    ) throws IOException {
        Path canonicalLobby = resolveCanonicalIfExists(lobbyFolder);

        if (canonicalCandidate.equals(canonicalLobby)
            || canonicalLobby.startsWith(canonicalCandidate)) {
            throw new IOException(
                "Refusing to touch permanent lobby world or its ancestor: " + canonicalCandidate
            );
        }

        // Reject any path under the lobby folder EXCEPT Paper 26.3's nested
        // <lobby>/dimensions/<namespace>/attempt_... which is already verified
        // by isLegitimatePaperWorldLayout.
        if (canonicalCandidate.startsWith(canonicalLobby)) {
            Path relFromLobby = canonicalLobby.relativize(canonicalCandidate);
            if (relFromLobby.getNameCount() != 3
                || !"dimensions".equals(relFromLobby.getName(0).toString())
                || "minecraft".equals(relFromLobby.getName(1).toString())) {
                throw new IOException(
                    "Refusing to touch protected subdirectory inside lobby world: " + canonicalCandidate
                );
            }
        }

        // If lobbyFolder is <level>/dimensions/minecraft/overworld, also protect <level> itself.
        Path lobbyLevelRoot = resolveLevelRootFromDimensionFolder(canonicalContainer, canonicalLobby);
        if (lobbyLevelRoot != null
            && (canonicalCandidate.equals(lobbyLevelRoot)
                || lobbyLevelRoot.startsWith(canonicalCandidate))) {
            throw new IOException(
                "Refusing to touch server primary level directory: " + canonicalCandidate
            );
        }

        if (pluginDataFolder != null) {
            Path canonicalData = resolveCanonicalIfExists(pluginDataFolder);
            if (canonicalCandidate.equals(canonicalData)
                || canonicalData.startsWith(canonicalCandidate)
                || canonicalCandidate.startsWith(canonicalData)) {
                throw new IOException(
                    "Refusing to touch plugin data directory: " + canonicalCandidate
                );
            }
        }
    }

    private static void verifyNotProtectedWorldSet(
        Path canonicalCandidate,
        Set<Path> protectedFolders,
        String role
    ) throws IOException {
        for (Path folder : protectedFolders) {
            if (folder == null) {
                continue;
            }
            Path canonicalProtected = resolveCanonicalIfExists(folder);
            if (canonicalCandidate.equals(canonicalProtected)
                || canonicalProtected.startsWith(canonicalCandidate)
                || canonicalCandidate.startsWith(canonicalProtected)) {
                throw new IOException(
                    "Refusing to delete a " + role + " world or related directory: " + canonicalCandidate
                );
            }
        }
    }

    private static void verifyNotPermanentFolder(
        Path canonicalContainer,
        Path canonicalCandidate,
        Set<Path> permanentFolders
    ) throws IOException {
        for (Path folder : permanentFolders) {
            if (folder == null) {
                continue;
            }
            Path canonicalPermanent = resolveCanonicalIfExists(folder);
            if (canonicalCandidate.equals(canonicalPermanent)
                || canonicalPermanent.startsWith(canonicalCandidate)) {
                throw new IOException(
                    "Refusing to delete a permanent or vanilla world: " + canonicalCandidate
                );
            }
            if (canonicalCandidate.startsWith(canonicalPermanent)
                && !isNestedAttemptUnderLevelRoot(canonicalContainer, canonicalPermanent, canonicalCandidate)) {
                throw new IOException(
                    "Refusing to delete subdirectory inside permanent world: " + canonicalCandidate
                );
            }
        }
    }

    private static void verifyNotLoadedWorldFolder(
        Path canonicalContainer,
        Path canonicalCandidate,
        Set<Path> loadedFolders
    ) throws IOException {
        for (Path folder : loadedFolders) {
            if (folder == null) {
                continue;
            }
            Path canonicalLoaded = resolveCanonicalIfExists(folder);
            if (canonicalCandidate.equals(canonicalLoaded)
                || canonicalLoaded.startsWith(canonicalCandidate)) {
                throw new IOException(
                    "Refusing to delete directory belonging to a loaded world: " + canonicalCandidate
                );
            }
            if (canonicalCandidate.startsWith(canonicalLoaded)
                && !isNestedAttemptUnderLevelRoot(canonicalContainer, canonicalLoaded, canonicalCandidate)) {
                throw new IOException(
                    "Refusing to delete subdirectory inside a loaded world: " + canonicalCandidate
                );
            }
        }
    }

    private static void verifyNoOtherLoadedConflict(
        Path canonicalContainer,
        Path canonicalCandidate,
        Set<Path> loadedFolders
    ) throws IOException {
        for (Path folder : loadedFolders) {
            if (folder == null) {
                continue;
            }
            Path canonicalLoaded = resolveCanonicalIfExists(folder);
            if (canonicalCandidate.equals(canonicalLoaded)) {
                // Allowed only during pre-unload check for the exact retired world itself.
                continue;
            }
            if (canonicalLoaded.startsWith(canonicalCandidate)) {
                throw new IOException(
                    "Refusing to unload/delete ancestor of another loaded world: " + canonicalCandidate
                );
            }
            if (canonicalCandidate.startsWith(canonicalLoaded)
                && !isNestedAttemptUnderLevelRoot(canonicalContainer, canonicalLoaded, canonicalCandidate)) {
                throw new IOException(
                    "Refusing to unload/delete directory nested inside another loaded world: "
                        + canonicalCandidate
                );
            }
        }
    }

    /**
     * In Paper 26.3+, the primary level root (e.g. {@code server/world}) is an
     * ancestor of {@code server/world/dimensions/<namespace>/attempt_...}.
     * Only that exact Paper dimension nesting relationship is permitted.
     */
    private static boolean isNestedAttemptUnderLevelRoot(
        Path canonicalContainer,
        Path possibleLevelRoot,
        Path canonicalCandidate
    ) {
        if (!possibleLevelRoot.startsWith(canonicalContainer)
            || possibleLevelRoot.equals(canonicalContainer)) {
            return false;
        }
        Path relRoot = canonicalContainer.relativize(possibleLevelRoot);
        if (relRoot.getNameCount() != 1) {
            return false;
        }
        Path relCandidate = possibleLevelRoot.relativize(canonicalCandidate);
        return relCandidate.getNameCount() == 3
            && "dimensions".equals(relCandidate.getName(0).toString())
            && !"minecraft".equals(relCandidate.getName(1).toString());
    }

    private static Path resolveLevelRootFromDimensionFolder(
        Path canonicalContainer,
        Path folder
    ) {
        if (!folder.startsWith(canonicalContainer) || folder.equals(canonicalContainer)) {
            return null;
        }
        Path rel = canonicalContainer.relativize(folder);
        if (rel.getNameCount() == 4 && "dimensions".equals(rel.getName(1).toString())) {
            return canonicalContainer.resolve(rel.getName(0));
        }
        return null;
    }

    private static Path resolveCanonicalIfExists(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        try {
            return normalized.toRealPath();
        } catch (IOException ignored) {
            return normalized;
        }
    }

    private static void recordIfExists(Set<Path> target, Path candidate) {
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            target.add(candidate.toAbsolutePath().normalize());
            try {
                target.add(candidate.toRealPath());
            } catch (IOException ignored) {
                // Keep lexical normalized path too (e.g. for broken symlinks).
            }
        }
    }
}
