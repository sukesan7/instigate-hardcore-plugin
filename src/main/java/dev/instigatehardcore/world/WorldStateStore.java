package dev.instigatehardcore.world;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

public final class WorldStateStore {

    private static final int FORMAT_VERSION = 2;

    private final Path stateFile;

    public WorldStateStore(
        Path stateFile
    ) {
        this.stateFile =
            Objects.requireNonNull(stateFile);
    }

    public synchronized WorldRotationState loadOrCreate(
        int currentAttempt
    ) throws IOException {

        if (currentAttempt < 1) {
            throw new IllegalArgumentException(
                "Current attempt must be at least one."
            );
        }

        if (!Files.exists(stateFile)) {
            long activeSeed =
                randomSeed();

            long standbySeed =
                randomSeedDifferentFrom(
                    activeSeed
                );

            WorldRotationState created =
                new WorldRotationState(
                    currentAttempt,
                    activeSeed,
                    currentAttempt + 1,
                    standbySeed,
                    WorldRotationPhase.STABLE
                );

            save(
                created
            );

            return created;
        }

        return load();
    }

    public synchronized WorldRotationState load()
        throws IOException {

        if (!Files.exists(stateFile)) {
            throw new IOException(
                "World state file does not exist: "
                    + stateFile
            );
        }

        Properties properties =
            new Properties();

        try (
            InputStream input =
                Files.newInputStream(
                    stateFile
                )
        ) {
            properties.load(
                input
            );
        }

        int version =
            parseInt(
                properties,
                "version"
            );

        /*
         * Version 1 existed before transactional rotation
         * recovery. Every v1 state represented a stable pipeline.
         */
        if (version == 1) {
            WorldRotationState migrated =
                new WorldRotationState(
                    parseInt(
                        properties,
                        "active.attempt"
                    ),
                    parseLong(
                        properties,
                        "active.seed"
                    ),
                    parseInt(
                        properties,
                        "standby.attempt"
                    ),
                    parseLong(
                        properties,
                        "standby.seed"
                    ),
                    WorldRotationPhase.STABLE
                );

            save(
                migrated
            );

            return migrated;
        }

        if (version != FORMAT_VERSION) {
            throw new IOException(
                "Unsupported world state version: "
                    + version
            );
        }

        int activeAttempt =
            parseInt(
                properties,
                "active.attempt"
            );

        long activeSeed =
            parseLong(
                properties,
                "active.seed"
            );

        int standbyAttempt =
            parseInt(
                properties,
                "standby.attempt"
            );

        long standbySeed =
            parseLong(
                properties,
                "standby.seed"
            );

        WorldRotationPhase phase =
            parsePhase(
                properties
            );

        try {
            return new WorldRotationState(
                activeAttempt,
                activeSeed,
                standbyAttempt,
                standbySeed,
                phase
            );
        } catch (
            IllegalArgumentException exception
        ) {
            throw new IOException(
                "Invalid world rotation state.",
                exception
            );
        }
    }

    public synchronized void save(
        WorldRotationState state
    ) throws IOException {

        Objects.requireNonNull(
            state
        );

        Path parent =
            stateFile.getParent();

        if (parent != null) {
            Files.createDirectories(
                parent
            );
        }

        Properties properties =
            new Properties();

        properties.setProperty(
            "version",
            Integer.toString(
                FORMAT_VERSION
            )
        );

        properties.setProperty(
            "phase",
            state.phase().name()
        );

        properties.setProperty(
            "active.attempt",
            Integer.toString(
                state.activeAttempt()
            )
        );

        properties.setProperty(
            "active.seed",
            Long.toString(
                state.activeSeed()
            )
        );

        properties.setProperty(
            "standby.attempt",
            Integer.toString(
                state.standbyAttempt()
            )
        );

        properties.setProperty(
            "standby.seed",
            Long.toString(
                state.standbySeed()
            )
        );

        Path temporaryFile =
            stateFile.resolveSibling(
                stateFile.getFileName()
                    + ".tmp"
            );

        try (
            OutputStream output =
                Files.newOutputStream(
                    temporaryFile
                )
        ) {
            properties.store(
                output,
                "Instigate Cafe Hardcore world rotation state"
            );
        }

        try {
            Files.move(
                temporaryFile,
                stateFile,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } catch (
            AtomicMoveNotSupportedException exception
        ) {
            Files.move(
                temporaryFile,
                stateFile,
                StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private WorldRotationPhase parsePhase(
        Properties properties
    ) throws IOException {

        String value =
            properties.getProperty(
                "phase"
            );

        if (value == null) {
            throw new IOException(
                "Missing world state property: phase"
            );
        }

        try {
            return WorldRotationPhase.valueOf(
                value
            );
        } catch (
            IllegalArgumentException exception
        ) {
            throw new IOException(
                "Invalid world rotation phase: "
                    + value,
                exception
            );
        }
    }

    private int parseInt(
        Properties properties,
        String key
    ) throws IOException {

        String value =
            properties.getProperty(
                key
            );

        if (value == null) {
            throw new IOException(
                "Missing world state property: "
                    + key
            );
        }

        try {
            return Integer.parseInt(
                value
            );
        } catch (
            NumberFormatException exception
        ) {
            throw new IOException(
                "Invalid integer for "
                    + key
                    + ": "
                    + value,
                exception
            );
        }
    }

    private long parseLong(
        Properties properties,
        String key
    ) throws IOException {

        String value =
            properties.getProperty(
                key
            );

        if (value == null) {
            throw new IOException(
                "Missing world state property: "
                    + key
            );
        }

        try {
            return Long.parseLong(
                value
            );
        } catch (
            NumberFormatException exception
        ) {
            throw new IOException(
                "Invalid long for "
                    + key
                    + ": "
                    + value,
                exception
            );
        }
    }

    private long randomSeed() {
        return ThreadLocalRandom
            .current()
            .nextLong();
    }

    private long randomSeedDifferentFrom(
        long otherSeed
    ) {
        long seed;

        do {
            seed =
                randomSeed();
        } while (
            seed == otherSeed
        );

        return seed;
    }

    public Path getStateFile() {
        return stateFile;
    }
}