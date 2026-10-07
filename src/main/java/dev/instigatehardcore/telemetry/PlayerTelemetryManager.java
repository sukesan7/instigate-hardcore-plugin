package dev.instigatehardcore.telemetry;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class PlayerTelemetryManager {

    private static final int FORMAT_VERSION =
        1;

    private final Path dataFile;

    private final Map<UUID, PlayerTelemetry> players =
        new HashMap<>();

    /*
     * Active sessions are intentionally memory-only.
     *
     * Their accumulated time is periodically checkpointed to
     * persistent storage.
     */
    private final Map<UUID, ActiveSession> activeSessions =
        new HashMap<>();

    public PlayerTelemetryManager(
        Path dataFile
    ) {
        this.dataFile =
            Objects.requireNonNull(
                dataFile
            );
    }

    public synchronized void load()
        throws IOException {

        players.clear();
        activeSessions.clear();

        if (!Files.exists(dataFile)) {
            return;
        }

        YamlConfiguration configuration =
            new YamlConfiguration();

        try {
            configuration.load(
                dataFile.toFile()
            );
        } catch (
            InvalidConfigurationException exception
        ) {
            throw new IOException(
                "Invalid player telemetry file.",
                exception
            );
        }

        int version =
            configuration.getInt(
                "version",
                -1
            );

        if (version != FORMAT_VERSION) {
            throw new IOException(
                "Unsupported player telemetry version: "
                    + version
            );
        }

        ConfigurationSection playersSection =
            configuration
                .getConfigurationSection(
                    "players"
                );

        if (playersSection == null) {
            return;
        }

        for (
            String uuidValue :
            playersSection.getKeys(
                false
            )
        ) {
            UUID uuid;

            try {
                uuid =
                    UUID.fromString(
                        uuidValue
                    );
            } catch (
                IllegalArgumentException exception
            ) {
                throw new IOException(
                    "Invalid telemetry player UUID: "
                        + uuidValue,
                    exception
                );
            }

            ConfigurationSection playerSection =
                playersSection
                    .getConfigurationSection(
                        uuidValue
                    );

            if (playerSection == null) {
                continue;
            }

            String name =
                playerSection.getString(
                    "name",
                    uuid.toString()
                );

            PlayerTelemetry telemetry =
                new PlayerTelemetry(
                    name
                );

            loadPlaytime(
                playerSection,
                telemetry
            );

            loadDeaths(
                playerSection,
                telemetry
            );

            players.put(
                uuid,
                telemetry
            );
        }
    }

    private void loadPlaytime(
        ConfigurationSection playerSection,
        PlayerTelemetry telemetry
    ) throws IOException {

        ConfigurationSection playtimeSection =
            playerSection
                .getConfigurationSection(
                    "playtime"
                );

        if (playtimeSection == null) {
            return;
        }

        for (
            String attemptValue :
            playtimeSection.getKeys(
                false
            )
        ) {
            int attempt;

            try {
                attempt =
                    Integer.parseInt(
                        attemptValue
                    );
            } catch (
                NumberFormatException exception
            ) {
                throw new IOException(
                    "Invalid playtime attempt: "
                        + attemptValue,
                    exception
                );
            }

            if (attempt < 1) {
                throw new IOException(
                    "Invalid playtime attempt: "
                        + attempt
                );
            }

            long playtime =
                playtimeSection.getLong(
                    attemptValue,
                    0L
                );

            if (playtime < 0) {
                throw new IOException(
                    "Negative playtime for attempt #"
                        + attempt
                );
            }

            telemetry.playtimeByAttempt.put(
                attempt,
                playtime
            );
        }
    }

    private void loadDeaths(
        ConfigurationSection playerSection,
        PlayerTelemetry telemetry
    ) throws IOException {

        List<Map<?, ?>> rawDeaths =
            playerSection.getMapList(
                "deaths"
            );

        for (
            Map<?, ?> raw :
            rawDeaths
        ) {
            int attempt =
                readInt(
                    raw,
                    "attempt"
                );

            long timestamp =
                readLong(
                    raw,
                    "timestamp"
                );

            String cause =
                readString(
                    raw,
                    "cause"
                );

            String message =
                readString(
                    raw,
                    "message"
                );

            telemetry.deaths.add(
                new PlayerDeathRecord(
                    attempt,
                    timestamp,
                    cause,
                    message
                )
            );
        }

        telemetry.deaths.sort(
            Comparator.comparingLong(
                PlayerDeathRecord::timestamp
            )
        );
    }

    public synchronized void save()
        throws IOException {

        Path parent =
            dataFile.getParent();

        if (parent != null) {
            Files.createDirectories(
                parent
            );
        }

        YamlConfiguration configuration =
            new YamlConfiguration();

        configuration.set(
            "version",
            FORMAT_VERSION
        );

        for (
            Map.Entry<UUID, PlayerTelemetry> entry :
            players.entrySet()
        ) {
            UUID uuid =
                entry.getKey();

            PlayerTelemetry telemetry =
                entry.getValue();

            String base =
                "players."
                    + uuid;

            configuration.set(
                base + ".name",
                telemetry.name
            );

            for (
                Map.Entry<Integer, Long> playtime :
                telemetry
                    .playtimeByAttempt
                    .entrySet()
            ) {
                configuration.set(
                    base
                        + ".playtime."
                        + playtime.getKey(),
                    playtime.getValue()
                );
            }

            List<Map<String, Object>> deaths =
                new ArrayList<>();

            for (
                PlayerDeathRecord death :
                telemetry.deaths
            ) {
                Map<String, Object> serialized =
                    new LinkedHashMap<>();

                serialized.put(
                    "attempt",
                    death.attempt()
                );

                serialized.put(
                    "timestamp",
                    death.timestamp()
                );

                serialized.put(
                    "cause",
                    death.cause()
                );

                serialized.put(
                    "message",
                    death.message()
                );

                deaths.add(
                    serialized
                );
            }

            configuration.set(
                base + ".deaths",
                deaths
            );
        }

        Path temporaryFile =
            dataFile.resolveSibling(
                dataFile
                    .getFileName()
                    + ".tmp"
            );

        configuration.save(
            temporaryFile.toFile()
        );

        try {
            Files.move(
                temporaryFile,
                dataFile,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } catch (
            AtomicMoveNotSupportedException exception
        ) {
            Files.move(
                temporaryFile,
                dataFile,
                StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    /**
     * Starts active-attempt playtime tracking.
     *
     * Calling this repeatedly for the same player and attempt
     * is safe and does not restart the timer.
     */
    public synchronized void beginSession(
        int attempt,
        UUID uuid,
        String name
    ) {
        validateAttempt(
            attempt
        );

        Objects.requireNonNull(
            uuid
        );

        validateName(
            name
        );

        PlayerTelemetry telemetry =
            getOrCreate(
                uuid,
                name
            );

        telemetry.name =
            name;

        ActiveSession existing =
            activeSessions.get(
                uuid
            );

        if (existing != null) {
            if (
                existing.attempt()
                    == attempt
            ) {
                return;
            }

            commitSession(
                uuid,
                existing,
                System.currentTimeMillis()
            );
        }

        activeSessions.put(
            uuid,
            new ActiveSession(
                attempt,
                System.currentTimeMillis()
            )
        );
    }

    /**
     * Ends one player's active playtime session.
     */
    public synchronized void endSession(
        UUID uuid
    ) throws IOException {

        ActiveSession session =
            activeSessions.remove(
                uuid
            );

        if (session == null) {
            return;
        }

        commitSession(
            uuid,
            session,
            System.currentTimeMillis()
        );

        save();
    }

    /**
     * Stops all sessions belonging to an attempt.
     *
     * This should happen immediately when an attempt enters
     * ENDING so the reset countdown does not count as playtime.
     */
    public synchronized void endAttemptSessions(
        int attempt
    ) throws IOException {

        validateAttempt(
            attempt
        );

        long now =
            System.currentTimeMillis();

        List<UUID> ending =
            activeSessions
                .entrySet()
                .stream()
                .filter(
                    entry ->
                        entry
                            .getValue()
                            .attempt()
                            == attempt
                )
                .map(
                    Map.Entry::getKey
                )
                .toList();

        if (ending.isEmpty()) {
            return;
        }

        for (
            UUID uuid :
            ending
        ) {
            ActiveSession session =
                activeSessions.remove(
                    uuid
                );

            if (session != null) {
                commitSession(
                    uuid,
                    session,
                    now
                );
            }
        }

        save();
    }

    /**
     * Persists elapsed time while leaving every active session
     * running.
     *
     * Run this periodically so a hard crash loses at most one
     * checkpoint interval of playtime.
     */
    public synchronized void checkpointActiveSessions()
        throws IOException {

        if (activeSessions.isEmpty()) {
            return;
        }

        long now =
            System.currentTimeMillis();

        List<UUID> uuids =
            new ArrayList<>(
                activeSessions.keySet()
            );

        for (
            UUID uuid :
            uuids
        ) {
            ActiveSession current =
                activeSessions.get(
                    uuid
                );

            if (current == null) {
                continue;
            }

            commitSession(
                uuid,
                current,
                now
            );

            activeSessions.put(
                uuid,
                new ActiveSession(
                    current.attempt(),
                    now
                )
            );
        }

        save();
    }

    /**
     * Used during plugin shutdown.
     */
    public synchronized void closeAllSessions()
        throws IOException {

        if (activeSessions.isEmpty()) {
            save();
            return;
        }

        long now =
            System.currentTimeMillis();

        for (
            Map.Entry<UUID, ActiveSession> entry :
            new ArrayList<>(
                activeSessions
                    .entrySet()
            )
        ) {
            commitSession(
                entry.getKey(),
                entry.getValue(),
                now
            );
        }

        activeSessions.clear();

        save();
    }

    public synchronized void recordDeath(
        UUID uuid,
        String name,
        PlayerDeathRecord death
    ) throws IOException {

        Objects.requireNonNull(
            uuid
        );

        validateName(
            name
        );

        Objects.requireNonNull(
            death
        );

        PlayerTelemetry telemetry =
            getOrCreate(
                uuid,
                name
            );

        telemetry.name =
            name;

        telemetry.deaths.add(
            death
        );

        telemetry.deaths.sort(
            Comparator.comparingLong(
                PlayerDeathRecord::timestamp
            )
        );

        /*
         * Death information is important enough to persist
         * immediately rather than waiting for a checkpoint.
         */
        save();
    }

    public synchronized long getPlaytimeMillis(
        UUID uuid,
        int attempt
    ) {
        validateAttempt(
            attempt
        );

        PlayerTelemetry telemetry =
            players.get(
                uuid
            );

        long persisted =
            telemetry == null
                ? 0L
                : telemetry
                    .playtimeByAttempt
                    .getOrDefault(
                        attempt,
                        0L
                    );

        ActiveSession session =
            activeSessions.get(
                uuid
            );

        if (
            session != null
                && session.attempt()
                    == attempt
        ) {
            persisted +=
                Math.max(
                    0L,
                    System.currentTimeMillis()
                        - session.startedAt()
                );
        }

        return persisted;
    }

    public synchronized long getTotalPlaytimeMillis(
        UUID uuid
    ) {
        PlayerTelemetry telemetry =
            players.get(
                uuid
            );

        long total =
            0L;

        if (telemetry != null) {
            for (
                long value :
                telemetry
                    .playtimeByAttempt
                    .values()
            ) {
                total +=
                    value;
            }
        }

        ActiveSession session =
            activeSessions.get(
                uuid
            );

        if (session != null) {
            total +=
                Math.max(
                    0L,
                    System.currentTimeMillis()
                        - session.startedAt()
                );
        }

        return total;
    }

    public synchronized List<PlayerDeathRecord> getLatestDeaths(
        UUID uuid,
        int limit
    ) {
        if (limit < 1) {
            return List.of();
        }

        PlayerTelemetry telemetry =
            players.get(
                uuid
            );

        if (telemetry == null) {
            return List.of();
        }

        return telemetry
            .deaths
            .stream()
            .sorted(
                Comparator
                    .comparingLong(
                        PlayerDeathRecord::timestamp
                    )
                    .reversed()
            )
            .limit(
                limit
            )
            .toList();
    }

    public synchronized List<PlayerDeathRecord> getDeaths(
        UUID uuid
    ) {
        PlayerTelemetry telemetry =
            players.get(
                uuid
            );

        if (telemetry == null) {
            return List.of();
        }

        return telemetry
            .deaths
            .stream()
            .sorted(
                Comparator
                    .comparingLong(
                        PlayerDeathRecord::timestamp
                    )
                    .reversed()
            )
            .toList();
    }

    public synchronized Optional<String> getMostCommonDeathCause(
        UUID uuid
    ) {
        PlayerTelemetry telemetry =
            players.get(
                uuid
            );

        if (
            telemetry == null
                || telemetry.deaths.isEmpty()
        ) {
            return Optional.empty();
        }

        Map<String, Long> counts =
            new HashMap<>();

        for (
            PlayerDeathRecord death :
            telemetry.deaths
        ) {
            counts.merge(
                death.cause(),
                1L,
                Long::sum
            );
        }

        return counts
            .entrySet()
            .stream()
            .max(
                Comparator
                    .<Map.Entry<String, Long>>
                        comparingLong(
                            Map.Entry::getValue
                        )
                    .thenComparing(
                        Map.Entry::getKey,
                        String.CASE_INSENSITIVE_ORDER
                    )
            )
            .map(
                Map.Entry::getKey
            );
    }

    public synchronized long getDeathCauseCount(
        UUID uuid,
        String cause
    ) {
        Objects.requireNonNull(
            cause
        );

        PlayerTelemetry telemetry =
            players.get(
                uuid
            );

        if (telemetry == null) {
            return 0L;
        }

        return telemetry
            .deaths
            .stream()
            .filter(
                death ->
                    death.cause()
                        .equalsIgnoreCase(
                            cause
                        )
            )
            .count();
    }

    public synchronized Optional<String> getLatestName(
        UUID uuid
    ) {
        PlayerTelemetry telemetry =
            players.get(
                uuid
            );

        if (telemetry == null) {
            return Optional.empty();
        }

        return Optional.of(
            telemetry.name
        );
    }

    public synchronized boolean hasActiveSession(
        UUID uuid
    ) {
        return activeSessions.containsKey(
            uuid
        );
    }

    private void commitSession(
        UUID uuid,
        ActiveSession session,
        long now
    ) {
        PlayerTelemetry telemetry =
            players.computeIfAbsent(
                uuid,
                ignored ->
                    new PlayerTelemetry(
                        uuid.toString()
                    )
            );

        long elapsed =
            Math.max(
                0L,
                now
                    - session.startedAt()
            );

        telemetry.playtimeByAttempt.merge(
            session.attempt(),
            elapsed,
            Long::sum
        );
    }

    private PlayerTelemetry getOrCreate(
        UUID uuid,
        String name
    ) {
        return players.computeIfAbsent(
            uuid,
            ignored ->
                new PlayerTelemetry(
                    name
                )
        );
    }

    private void validateAttempt(
        int attempt
    ) {
        if (attempt < 1) {
            throw new IllegalArgumentException(
                "Attempt number must be at least one."
            );
        }
    }

    private void validateName(
        String name
    ) {
        Objects.requireNonNull(
            name
        );

        if (name.isBlank()) {
            throw new IllegalArgumentException(
                "Player name cannot be blank."
            );
        }
    }

    private int readInt(
        Map<?, ?> map,
        String key
    ) throws IOException {

        Object value =
            map.get(
                key
            );

        if (value instanceof Number number) {
            return number.intValue();
        }

        throw new IOException(
            "Missing or invalid telemetry field: "
                + key
        );
    }

    private long readLong(
        Map<?, ?> map,
        String key
    ) throws IOException {

        Object value =
            map.get(
                key
            );

        if (value instanceof Number number) {
            return number.longValue();
        }

        throw new IOException(
            "Missing or invalid telemetry field: "
                + key
        );
    }

    private String readString(
        Map<?, ?> map,
        String key
    ) throws IOException {

        Object value =
            map.get(
                key
            );

        if (value instanceof String string) {
            return string;
        }

        throw new IOException(
            "Missing or invalid telemetry field: "
                + key
        );
    }

    public Path getDataFile() {
        return dataFile;
    }

    private static final class PlayerTelemetry {

        private String name;

        private final Map<Integer, Long> playtimeByAttempt =
            new HashMap<>();

        private final List<PlayerDeathRecord> deaths =
            new ArrayList<>();

        private PlayerTelemetry(
            String name
        ) {
            this.name =
                Objects.requireNonNull(
                    name
                );
        }
    }

    private record ActiveSession(
        int attempt,
        long startedAt
    ) {
    }
}