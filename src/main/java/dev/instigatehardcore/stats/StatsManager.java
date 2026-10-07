package dev.instigatehardcore.stats;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

public final class StatsManager {

    private static final int DEFAULT_ATTEMPT = 1;

    private final Path statsFile;
    private final Map<UUID, PlayerStats> players = new HashMap<>();

    private int currentAttempt = DEFAULT_ATTEMPT;

    public StatsManager(Path statsFile) {
        this.statsFile = Objects.requireNonNull(statsFile);
    }

    /**
     * Loads persistent campaign statistics from disk.
     *
     * If no stats file exists yet, a fresh campaign is initialized.
     */
    public synchronized void load() throws IOException {
        players.clear();
        currentAttempt = DEFAULT_ATTEMPT;

        if (!Files.exists(statsFile)) {
            save();
            return;
        }

        Properties properties = new Properties();

        try (InputStream input = Files.newInputStream(statsFile)) {
            properties.load(input);
        }

        currentAttempt = parsePositiveInt(
            properties.getProperty("attempt"),
            DEFAULT_ATTEMPT
        );

        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith("player.") || !key.endsWith(".name")) {
                continue;
            }

            String uuidText = key.substring(
                "player.".length(),
                key.length() - ".name".length()
            );

            try {
                UUID uuid = UUID.fromString(uuidText);

                String name = properties.getProperty(key, "Unknown");

                int deaths = parseNonNegativeInt(
                    properties.getProperty(
                        "player." + uuid + ".deaths"
                    ),
                    0
                );

                players.put(
                    uuid,
                    new PlayerStats(uuid, name, deaths)
                );
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed UUID entries rather than failing
                // the entire campaign stats file.
            }
        }
    }

    /**
     * Saves all campaign statistics to disk.
     */
    public synchronized void save() throws IOException {
        Path parent = statsFile.getParent();

        if (parent != null) {
            Files.createDirectories(parent);
        }

        Properties properties = new Properties();

        properties.setProperty(
            "attempt",
            Integer.toString(currentAttempt)
        );

        for (PlayerStats player : players.values()) {
            String prefix = "player." + player.uuid();

            properties.setProperty(
                prefix + ".name",
                player.name()
            );

            properties.setProperty(
                prefix + ".deaths",
                Integer.toString(player.deaths())
            );
        }

        Path temporaryFile = statsFile.resolveSibling(
            statsFile.getFileName() + ".tmp"
        );

        try (OutputStream output = Files.newOutputStream(temporaryFile)) {
            properties.store(
                output,
                "Instigate Cafe Hardcore persistent statistics"
            );
        }

        try {
            Files.move(
                temporaryFile,
                statsFile,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(
                temporaryFile,
                statsFile,
                StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    public synchronized int getCurrentAttempt() {
        return currentAttempt;
    }

    public synchronized int getDeaths(UUID uuid) {
        PlayerStats player = players.get(uuid);

        if (player == null) {
            return 0;
        }

        return player.deaths();
    }

    public synchronized PlayerStats recordDeath(
        UUID uuid,
        String name
    ) throws IOException {
        Objects.requireNonNull(uuid);
        Objects.requireNonNull(name);

        PlayerStats existing = players.get(uuid);

        int newDeathCount = existing == null
            ? 1
            : existing.deaths() + 1;

        PlayerStats updated = new PlayerStats(
            uuid,
            name,
            newDeathCount
        );

        players.put(uuid, updated);

        save();

        return updated;
    }

    /**
     * Advances to the next hardcore attempt and immediately
     * persists the new attempt number.
     */
    public synchronized int advanceAttempt() throws IOException {
        currentAttempt++;

        save();

        return currentAttempt;
    }

    /**
     * Returns all known players ordered by death count,
     * highest first.
     */
    public synchronized List<PlayerStats> getPlayersByDeaths() {
        List<PlayerStats> result = new ArrayList<>(players.values());

        result.sort(
            Comparator
                .comparingInt(PlayerStats::deaths)
                .reversed()
                .thenComparing(PlayerStats::name)
        );

        return List.copyOf(result);
    }

    public synchronized PlayerStats ensurePlayer(
        UUID uuid,
        String name
    ) throws IOException {
        Objects.requireNonNull(uuid);
        Objects.requireNonNull(name);

        PlayerStats existing = players.get(uuid);

        if (existing == null) {
            PlayerStats created = new PlayerStats(
                uuid,
                name,
                0
            );

            players.put(uuid, created);
            save();

            return created;
        }

        /*
        * Keep the latest Minecraft username while preserving
        * the player's persistent death count.
        */
        if (!existing.name().equals(name)) {
            PlayerStats updated = new PlayerStats(
                uuid,
                name,
                existing.deaths()
            );

            players.put(uuid, updated);
            save();

            return updated;
        }

        return existing;
    }

    private int parsePositiveInt(
        String value,
        int fallback
    ) {
        try {
            int parsed = Integer.parseInt(value);

            return parsed > 0
                ? parsed
                : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private int parseNonNegativeInt(
        String value,
        int fallback
    ) {
        try {
            int parsed = Integer.parseInt(value);

            return parsed >= 0
                ? parsed
                : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}