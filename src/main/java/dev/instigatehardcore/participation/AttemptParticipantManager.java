package dev.instigatehardcore.participation;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import java.time.Instant;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AttemptParticipantManager {

    private static final int FORMAT_VERSION = 1;

    private static final Pattern ATTEMPT_KEY =
        Pattern.compile(
            "^attempt\\.(\\d+)\\.exists$"
        );

    private static final Pattern PARTICIPANT_NAME_KEY =
        Pattern.compile(
            "^attempt\\.(\\d+)\\.participant\\.([0-9a-fA-F-]{36})\\.name$"
        );

    private final Path dataFile;

    private final Map<
        Integer,
        Map<UUID, AttemptParticipant>
    > participantsByAttempt =
        new HashMap<>();

    public AttemptParticipantManager(
        Path dataFile
    ) {
        this.dataFile =
            Objects.requireNonNull(dataFile);
    }

    public synchronized void load()
        throws IOException {

        participantsByAttempt.clear();

        if (!Files.exists(dataFile)) {
            return;
        }

        Properties properties =
            new Properties();

        try (
            InputStream input =
                Files.newInputStream(
                    dataFile
                )
        ) {
            properties.load(
                input
            );
        }

        String versionValue =
            properties.getProperty(
                "version"
            );

        if (versionValue == null) {
            throw new IOException(
                "Missing participant data version."
            );
        }

        int version;

        try {
            version =
                Integer.parseInt(
                    versionValue
                );
        } catch (
            NumberFormatException exception
        ) {
            throw new IOException(
                "Invalid participant data version: "
                    + versionValue,
                exception
            );
        }

        if (version != FORMAT_VERSION) {
            throw new IOException(
                "Unsupported participant data version: "
                    + version
            );
        }

        /*
         * Load empty attempt records first.
         */
        for (
            String key :
            properties.stringPropertyNames()
        ) {
            Matcher matcher =
                ATTEMPT_KEY.matcher(
                    key
                );

            if (!matcher.matches()) {
                continue;
            }

            int attempt =
                parseAttempt(
                    matcher.group(1)
                );

            participantsByAttempt
                .computeIfAbsent(
                    attempt,
                    ignored ->
                        new HashMap<>()
                );
        }

        /*
         * Load participants.
         */
        for (
            String key :
            properties.stringPropertyNames()
        ) {
            Matcher matcher =
                PARTICIPANT_NAME_KEY.matcher(
                    key
                );

            if (!matcher.matches()) {
                continue;
            }

            int attempt =
                parseAttempt(
                    matcher.group(1)
                );

            UUID uuid;

            try {
                uuid =
                    UUID.fromString(
                        matcher.group(2)
                    );
            } catch (
                IllegalArgumentException exception
            ) {
                throw new IOException(
                    "Invalid participant UUID in key: "
                        + key,
                    exception
                );
            }

            String name =
                properties.getProperty(
                    key
                );

            if (
                name == null
                    || name.isBlank()
            ) {
                throw new IOException(
                    "Missing participant name for "
                        + uuid
                        + "."
                );
            }

            String joinedKey =
                joinedKey(
                    attempt,
                    uuid
                );

            String joinedValue =
                properties.getProperty(
                    joinedKey
                );

            if (joinedValue == null) {
                throw new IOException(
                    "Missing join timestamp for "
                        + uuid
                        + " in attempt #"
                        + attempt
                        + "."
                );
            }

            long joinedAt;

            try {
                joinedAt =
                    Long.parseLong(
                        joinedValue
                    );
            } catch (
                NumberFormatException exception
            ) {
                throw new IOException(
                    "Invalid join timestamp for "
                        + uuid
                        + ".",
                    exception
                );
            }

            AttemptParticipant participant =
                new AttemptParticipant(
                    uuid,
                    name,
                    joinedAt
                );

            participantsByAttempt
                .computeIfAbsent(
                    attempt,
                    ignored ->
                        new HashMap<>()
                )
                .put(
                    uuid,
                    participant
                );
        }
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

        Properties properties =
            new Properties();

        properties.setProperty(
            "version",
            Integer.toString(
                FORMAT_VERSION
            )
        );

        List<Integer> attempts =
            participantsByAttempt
                .keySet()
                .stream()
                .sorted()
                .toList();

        for (int attempt : attempts) {
            properties.setProperty(
                attemptExistsKey(
                    attempt
                ),
                "true"
            );

            for (
                AttemptParticipant participant :
                getParticipants(attempt)
            ) {
                properties.setProperty(
                    nameKey(
                        attempt,
                        participant.uuid()
                    ),
                    participant.name()
                );

                properties.setProperty(
                    joinedKey(
                        attempt,
                        participant.uuid()
                    ),
                    Long.toString(
                        participant.firstJoinedAt()
                    )
                );
            }
        }

        Path temporaryFile =
            dataFile.resolveSibling(
                dataFile.getFileName()
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
                "Instigate Cafe Hardcore attempt participants"
            );
        }

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
     * Makes sure an attempt exists without clearing anything
     * already recorded for it.
     *
     * This is important for normal Paper restarts.
     */
    public synchronized void ensureAttempt(
        int attempt
    ) throws IOException {

        validateAttempt(
            attempt
        );

        if (
            participantsByAttempt
                .containsKey(
                    attempt
                )
        ) {
            return;
        }

        participantsByAttempt.put(
            attempt,
            new HashMap<>()
        );

        save();
    }

    /**
     * Adds a player to an attempt exactly once.
     *
     * Rejoining updates their latest username but does not
     * change their original participation timestamp.
     *
     * @return true when this is the player's first participation
     *         in this attempt
     */
    public synchronized boolean recordParticipant(
        int attempt,
        UUID uuid,
        String name
    ) throws IOException {

        validateAttempt(
            attempt
        );

        Objects.requireNonNull(
            uuid
        );

        Objects.requireNonNull(
            name
        );

        if (name.isBlank()) {
            throw new IllegalArgumentException(
                "Participant name cannot be blank."
            );
        }

        Map<UUID, AttemptParticipant> participants =
            participantsByAttempt
                .computeIfAbsent(
                    attempt,
                    ignored ->
                        new HashMap<>()
                );

        AttemptParticipant existing =
            participants.get(
                uuid
            );

        if (existing != null) {
            /*
             * Preserve original join time while keeping the
             * latest username.
             */
            if (
                !existing.name()
                    .equals(name)
            ) {
                participants.put(
                    uuid,
                    new AttemptParticipant(
                        uuid,
                        name,
                        existing.firstJoinedAt()
                    )
                );

                save();
            }

            return false;
        }

        participants.put(
            uuid,
            new AttemptParticipant(
                uuid,
                name,
                Instant.now()
                    .toEpochMilli()
            )
        );

        save();

        return true;
    }

    public synchronized List<AttemptParticipant> getParticipants(
        int attempt
    ) {
        Map<UUID, AttemptParticipant> participants =
            participantsByAttempt.get(
                attempt
            );

        if (participants == null) {
            return List.of();
        }

        List<AttemptParticipant> result =
            new ArrayList<>(
                participants.values()
            );

        result.sort(
            Comparator
                .comparingLong(
                    AttemptParticipant::firstJoinedAt
                )
                .thenComparing(
                    AttemptParticipant::name,
                    String.CASE_INSENSITIVE_ORDER
                )
        );

        return List.copyOf(
            result
        );
    }

    public synchronized int getParticipantCount(
        int attempt
    ) {
        Map<UUID, AttemptParticipant> participants =
            participantsByAttempt.get(
                attempt
            );

        return participants == null
            ? 0
            : participants.size();
    }

    public synchronized boolean hasParticipant(
        int attempt,
        UUID uuid
    ) {
        Map<UUID, AttemptParticipant> participants =
            participantsByAttempt.get(
                attempt
            );

        return participants != null
            && participants.containsKey(
                uuid
            );
    }

    public Path getDataFile() {
        return dataFile;
    }

    private int parseAttempt(
        String value
    ) throws IOException {

        try {
            int attempt =
                Integer.parseInt(
                    value
                );

            validateAttempt(
                attempt
            );

            return attempt;
        } catch (
            NumberFormatException exception
        ) {
            throw new IOException(
                "Invalid attempt number: "
                    + value,
                exception
            );
        }
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

    private String attemptExistsKey(
        int attempt
    ) {
        return "attempt."
            + attempt
            + ".exists";
    }

    private String nameKey(
        int attempt,
        UUID uuid
    ) {
        return "attempt."
            + attempt
            + ".participant."
            + uuid
            + ".name";
    }

    private String joinedKey(
        int attempt,
        UUID uuid
    ) {
        return "attempt."
            + attempt
            + ".participant."
            + uuid
            + ".joined-at";
    }
}