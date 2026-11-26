package dev.instigatehardcore.world;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/**
 * Persistent ownership marker stored inside each attempt world directory
 * generated or safely migrated by InstigateHardcore.
 *
 * Deletion refuses any directory lacking a valid, non-symlink marker matching
 * the exact retired world's identity.
 */
public record WorldOwnershipMarker(
    int schemaVersion,
    String pluginName,
    String namespace,
    String worldKey,
    UUID worldUid,
    int attemptNumber,
    String dimension,
    long seed,
    long createdAtMillis,
    boolean migrated
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String PLUGIN_NAME = "InstigateHardcore";
    public static final String FILE_NAME = ".instigatehardcore-owner.properties";

    private static final Set<String> VALID_DIMENSIONS =
        Set.of("overworld", "nether", "end");

    public WorldOwnershipMarker {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                "Unsupported ownership marker schema version: " + schemaVersion
            );
        }
        Objects.requireNonNull(pluginName, "pluginName");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(worldKey, "worldKey");
        Objects.requireNonNull(worldUid, "worldUid");
        Objects.requireNonNull(dimension, "dimension");

        if (pluginName.isBlank()) {
            throw new IllegalArgumentException("pluginName cannot be blank.");
        }
        if (namespace.isBlank()) {
            throw new IllegalArgumentException("namespace cannot be blank.");
        }
        if (worldKey.isBlank()) {
            throw new IllegalArgumentException("worldKey cannot be blank.");
        }
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be positive.");
        }
        if (!VALID_DIMENSIONS.contains(dimension)) {
            throw new IllegalArgumentException("Invalid attempt dimension: " + dimension);
        }
        if (createdAtMillis < 0L) {
            throw new IllegalArgumentException("createdAtMillis cannot be negative.");
        }
    }

    public static WorldOwnershipMarker create(
        WorldDeletionGuard.ExpectedWorldIdentity identity,
        long createdAtMillis,
        boolean migrated
    ) {
        Objects.requireNonNull(identity, "identity");
        return new WorldOwnershipMarker(
            CURRENT_SCHEMA_VERSION,
            PLUGIN_NAME,
            identity.namespace(),
            identity.worldKey(),
            identity.worldUid(),
            identity.attemptNumber(),
            identity.dimension(),
            identity.seed(),
            createdAtMillis,
            migrated
        );
    }

    public boolean matches(WorldDeletionGuard.ExpectedWorldIdentity expected) {
        Objects.requireNonNull(expected, "expected");
        return schemaVersion == CURRENT_SCHEMA_VERSION
            && PLUGIN_NAME.equals(pluginName)
            && expected.namespace().equals(namespace)
            && expected.worldKey().equals(worldKey)
            && expected.worldUid().equals(worldUid)
            && expected.attemptNumber() == attemptNumber
            && expected.dimension().equals(dimension)
            && expected.seed() == seed;
    }

    public static Optional<WorldOwnershipMarker> read(Path worldFolder) throws IOException {
        Objects.requireNonNull(worldFolder, "worldFolder");

        if (Files.isSymbolicLink(worldFolder)) {
            throw new IOException("Refusing to read ownership marker from symbolic link: " + worldFolder);
        }
        if (!Files.isDirectory(worldFolder, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World folder is not a real directory: " + worldFolder);
        }

        Path markerPath = worldFolder.resolve(FILE_NAME);
        if (!Files.exists(markerPath, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (Files.isSymbolicLink(markerPath)) {
            throw new IOException("Refusing symbolic-link ownership marker: " + markerPath);
        }
        if (!Files.isRegularFile(markerPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Ownership marker is not a regular file: " + markerPath);
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(markerPath, LinkOption.NOFOLLOW_LINKS)) {
            properties.load(input);
        }

        int schema = parseInt(properties, "schema");
        if (schema != CURRENT_SCHEMA_VERSION) {
            throw new IOException("Unsupported ownership marker schema: " + schema);
        }

        String plugin = requireNonBlank(properties, "plugin");
        String namespace = requireNonBlank(properties, "namespace").toLowerCase(Locale.ROOT);
        String worldKey = requireNonBlank(properties, "world.key").toLowerCase(Locale.ROOT);
        UUID worldUid = parseUuid(properties, "world.uid");
        int attempt = parseInt(properties, "attempt");
        String dimension = requireNonBlank(properties, "dimension").toLowerCase(Locale.ROOT);
        long seed = parseLong(properties, "seed");
        long createdAt = parseLong(properties, "created.at");
        boolean migrated = Boolean.parseBoolean(properties.getProperty("migrated", "false"));

        try {
            return Optional.of(new WorldOwnershipMarker(
                schema,
                plugin,
                namespace,
                worldKey,
                worldUid,
                attempt,
                dimension,
                seed,
                createdAt,
                migrated
            ));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid world ownership marker in " + markerPath, exception);
        }
    }

    public static void write(Path worldFolder, WorldOwnershipMarker marker) throws IOException {
        Objects.requireNonNull(worldFolder, "worldFolder");
        Objects.requireNonNull(marker, "marker");

        if (Files.isSymbolicLink(worldFolder)) {
            throw new IOException("Refusing to write ownership marker to symbolic link: " + worldFolder);
        }
        if (!Files.isDirectory(worldFolder, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World folder is not a real directory: " + worldFolder);
        }

        Path markerPath = worldFolder.resolve(FILE_NAME);
        if (Files.isSymbolicLink(markerPath)) {
            throw new IOException("Refusing to overwrite symbolic-link ownership marker: " + markerPath);
        }

        Path tempPath = worldFolder.resolve(FILE_NAME + ".tmp");
        if (Files.isSymbolicLink(tempPath)) {
            throw new IOException("Refusing to write temporary ownership marker through symbolic link: " + tempPath);
        }

        Properties properties = new Properties();
        properties.setProperty("schema", Integer.toString(marker.schemaVersion()));
        properties.setProperty("plugin", marker.pluginName());
        properties.setProperty("namespace", marker.namespace());
        properties.setProperty("world.key", marker.worldKey());
        properties.setProperty("world.uid", marker.worldUid().toString());
        properties.setProperty("attempt", Integer.toString(marker.attemptNumber()));
        properties.setProperty("dimension", marker.dimension());
        properties.setProperty("seed", Long.toString(marker.seed()));
        properties.setProperty("created.at", Long.toString(marker.createdAtMillis()));
        properties.setProperty("migrated", Boolean.toString(marker.migrated()));

        try (OutputStream output = Files.newOutputStream(
            tempPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )) {
            properties.store(output, "Instigate Hardcore managed world ownership marker");
        }

        try {
            Files.move(
                tempPath,
                markerPath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(
                tempPath,
                markerPath,
                StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private static String requireNonBlank(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IOException("Missing ownership marker property: " + key);
        }
        return value.trim();
    }

    private static int parseInt(Properties properties, String key) throws IOException {
        String raw = requireNonBlank(properties, key);
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid integer ownership marker property " + key + ": " + raw, exception);
        }
    }

    private static long parseLong(Properties properties, String key) throws IOException {
        String raw = requireNonBlank(properties, key);
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid long ownership marker property " + key + ": " + raw, exception);
        }
    }

    private static UUID parseUuid(Properties properties, String key) throws IOException {
        String raw = requireNonBlank(properties, key);
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid UUID ownership marker property " + key + ": " + raw, exception);
        }
    }
}
