package dev.instigatehardcore.participation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class AttemptParticipantManagerTest {

    @TempDir
    Path tempDirectory;

    @Test
    void recordsParticipantOnlyOncePerAttempt()
        throws Exception {

        Path dataFile =
            tempDirectory.resolve(
                "attempt-participants.properties"
            );

        AttemptParticipantManager manager =
            new AttemptParticipantManager(
                dataFile
            );

        UUID uuid =
            UUID.randomUUID();

        manager.ensureAttempt(
            12
        );

        boolean first =
            manager.recordParticipant(
                12,
                uuid,
                "Sukesan"
            );

        boolean second =
            manager.recordParticipant(
                12,
                uuid,
                "Sukesan"
            );

        assertTrue(
            first
        );

        assertFalse(
            second
        );

        assertEquals(
            1,
            manager.getParticipantCount(
                12
            )
        );

        assertTrue(
            manager.hasParticipant(
                12,
                uuid
            )
        );
    }

    @Test
    void preservesFirstJoinTimeWhenPlayerNameChanges()
        throws Exception {

        Path dataFile =
            tempDirectory.resolve(
                "attempt-participants.properties"
            );

        AttemptParticipantManager manager =
            new AttemptParticipantManager(
                dataFile
            );

        UUID uuid =
            UUID.randomUUID();

        manager.recordParticipant(
            5,
            uuid,
            "OldName"
        );

        AttemptParticipant original =
            manager
                .getParticipants(
                    5
                )
                .getFirst();

        long firstJoinedAt =
            original.firstJoinedAt();

        boolean addedAgain =
            manager.recordParticipant(
                5,
                uuid,
                "NewName"
            );

        AttemptParticipant renamed =
            manager
                .getParticipants(
                    5
                )
                .getFirst();

        assertFalse(
            addedAgain
        );

        assertEquals(
            "NewName",
            renamed.name()
        );

        assertEquals(
            firstJoinedAt,
            renamed.firstJoinedAt()
        );

        assertEquals(
            1,
            manager.getParticipantCount(
                5
            )
        );
    }

    @Test
    void persistsParticipantsAcrossReload()
        throws Exception {

        Path dataFile =
            tempDirectory.resolve(
                "attempt-participants.properties"
            );

        UUID playerOne =
            UUID.randomUUID();

        UUID playerTwo =
            UUID.randomUUID();

        AttemptParticipantManager writer =
            new AttemptParticipantManager(
                dataFile
            );

        writer.ensureAttempt(
            10
        );

        writer.recordParticipant(
            10,
            playerOne,
            "PlayerOne"
        );

        writer.ensureAttempt(
            11
        );

        writer.recordParticipant(
            11,
            playerOne,
            "PlayerOne"
        );

        writer.recordParticipant(
            11,
            playerTwo,
            "PlayerTwo"
        );

        AttemptParticipantManager reader =
            new AttemptParticipantManager(
                dataFile
            );

        reader.load();

        assertEquals(
            1,
            reader.getParticipantCount(
                10
            )
        );

        assertEquals(
            2,
            reader.getParticipantCount(
                11
            )
        );

        assertTrue(
            reader.hasParticipant(
                10,
                playerOne
            )
        );

        assertTrue(
            reader.hasParticipant(
                11,
                playerOne
            )
        );

        assertTrue(
            reader.hasParticipant(
                11,
                playerTwo
            )
        );
    }

    @Test
    void attemptsPlayedUsesActualParticipationHistory()
        throws Exception {

        Path dataFile =
            tempDirectory.resolve(
                "attempt-participants.properties"
            );

        AttemptParticipantManager manager =
            new AttemptParticipantManager(
                dataFile
            );

        UUID player =
            UUID.randomUUID();

        /*
         * Attempt #2 exists, but the player never joined it.
         */
        manager.ensureAttempt(
            2
        );

        manager.recordParticipant(
            3,
            player,
            "Player"
        );

        manager.ensureAttempt(
            4
        );

        manager.recordParticipant(
            7,
            player,
            "Player"
        );

        assertEquals(
            2,
            manager.getAttemptsPlayed(
                player
            )
        );

        assertEquals(
            List.of(
                3,
                7
            ),
            manager.getAttemptsPlayedList(
                player
            )
        );
    }

    @Test
    void emptyAttemptsPersistAcrossReload()
        throws Exception {

        Path dataFile =
            tempDirectory.resolve(
                "attempt-participants.properties"
            );

        AttemptParticipantManager writer =
            new AttemptParticipantManager(
                dataFile
            );

        writer.ensureAttempt(
            25
        );

        assertTrue(
            Files.exists(
                dataFile
            )
        );

        AttemptParticipantManager reader =
            new AttemptParticipantManager(
                dataFile
            );

        reader.load();

        assertEquals(
            0,
            reader.getParticipantCount(
                25
            )
        );

        /*
         * This should be a no-op rather than recreating or
         * destroying the attempt.
         */
        reader.ensureAttempt(
            25
        );

        assertEquals(
            0,
            reader.getParticipantCount(
                25
            )
        );
    }
}