package dev.instigatehardcore.telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class PlayerTelemetryManagerTest {

    @TempDir
    Path tempDirectory;

    @Test
    void persistsDeathHistoryAcrossReload()
        throws Exception {

        Path dataFile =
            tempDirectory.resolve(
                "player-telemetry.yml"
            );

        UUID uuid =
            UUID.randomUUID();

        PlayerTelemetryManager writer =
            new PlayerTelemetryManager(
                dataFile
            );

        writer.recordDeath(
            uuid,
            "Sukesan",
            new PlayerDeathRecord(
                4,
                1_000L,
                "Creeper",
                "Sukesan was blown up by Creeper"
            )
        );

        writer.recordDeath(
            uuid,
            "Sukesan",
            new PlayerDeathRecord(
                7,
                2_000L,
                "Lava",
                "Sukesan tried to swim in lava"
            )
        );

        PlayerTelemetryManager reader =
            new PlayerTelemetryManager(
                dataFile
            );

        reader.load();

        List<PlayerDeathRecord> deaths =
            reader.getDeaths(
                uuid
            );

        assertEquals(
            2,
            deaths.size()
        );

        /*
         * getDeaths() returns newest first.
         */
        assertEquals(
            7,
            deaths.get(0)
                .attempt()
        );

        assertEquals(
            "Lava",
            deaths.get(0)
                .cause()
        );

        assertEquals(
            4,
            deaths.get(1)
                .attempt()
        );

        assertEquals(
            "Creeper",
            deaths.get(1)
                .cause()
        );
    }

    @Test
    void returnsLatestDeathsNewestFirst()
        throws Exception {

        Path dataFile =
            tempDirectory.resolve(
                "player-telemetry.yml"
            );

        UUID uuid =
            UUID.randomUUID();

        PlayerTelemetryManager manager =
            new PlayerTelemetryManager(
                dataFile
            );

        manager.recordDeath(
            uuid,
            "Player",
            new PlayerDeathRecord(
                1,
                100L,
                "Zombie",
                "Death one"
            )
        );

        manager.recordDeath(
            uuid,
            "Player",
            new PlayerDeathRecord(
                2,
                200L,
                "Creeper",
                "Death two"
            )
        );

        manager.recordDeath(
            uuid,
            "Player",
            new PlayerDeathRecord(
                3,
                300L,
                "Fall",
                "Death three"
            )
        );

        List<PlayerDeathRecord> latest =
            manager.getLatestDeaths(
                uuid,
                2
            );

        assertEquals(
            2,
            latest.size()
        );

        assertEquals(
            3,
            latest.get(0)
                .attempt()
        );

        assertEquals(
            2,
            latest.get(1)
                .attempt()
        );
    }

    @Test
    void calculatesMostCommonDeathCause()
        throws Exception {

        Path dataFile =
            tempDirectory.resolve(
                "player-telemetry.yml"
            );

        UUID uuid =
            UUID.randomUUID();

        PlayerTelemetryManager manager =
            new PlayerTelemetryManager(
                dataFile
            );

        manager.recordDeath(
            uuid,
            "Player",
            new PlayerDeathRecord(
                1,
                100L,
                "Creeper",
                "Death one"
            )
        );

        manager.recordDeath(
            uuid,
            "Player",
            new PlayerDeathRecord(
                2,
                200L,
                "Fall",
                "Death two"
            )
        );

        manager.recordDeath(
            uuid,
            "Player",
            new PlayerDeathRecord(
                3,
                300L,
                "Creeper",
                "Death three"
            )
        );

        assertEquals(
            "Creeper",
            manager
                .getMostCommonDeathCause(
                    uuid
                )
                .orElseThrow()
        );

        assertEquals(
            2L,
            manager.getDeathCauseCount(
                uuid,
                "Creeper"
            )
        );

        /*
         * Cause lookup is intentionally case-insensitive.
         */
        assertEquals(
            2L,
            manager.getDeathCauseCount(
                uuid,
                "creeper"
            )
        );
    }

    @Test
    void tracksPlaytimeAcrossSeparateAttempts()
        throws Exception {

        Path dataFile =
            tempDirectory.resolve(
                "player-telemetry.yml"
            );

        UUID uuid =
            UUID.randomUUID();

        PlayerTelemetryManager manager =
            new PlayerTelemetryManager(
                dataFile
            );

        manager.beginSession(
            8,
            uuid,
            "Player"
        );

        Thread.sleep(
            25L
        );

        manager.endSession(
            uuid
        );

        long attemptEight =
            manager.getPlaytimeMillis(
                uuid,
                8
            );

        assertTrue(
            attemptEight > 0L
        );

        manager.beginSession(
            9,
            uuid,
            "Player"
        );

        Thread.sleep(
            25L
        );

        manager.endSession(
            uuid
        );

        long attemptNine =
            manager.getPlaytimeMillis(
                uuid,
                9
            );

        assertTrue(
            attemptNine > 0L
        );

        assertTrue(
            manager.getTotalPlaytimeMillis(
                uuid
            )
                >= attemptEight
                    + attemptNine
        );
    }

    @Test
    void beginningSameSessionTwiceDoesNotResetTimer()
        throws Exception {

        Path dataFile =
            tempDirectory.resolve(
                "player-telemetry.yml"
            );

        UUID uuid =
            UUID.randomUUID();

        PlayerTelemetryManager manager =
            new PlayerTelemetryManager(
                dataFile
            );

        manager.beginSession(
            15,
            uuid,
            "Player"
        );

        Thread.sleep(
            20L
        );

        /*
         * Same player + same attempt must be idempotent.
         */
        manager.beginSession(
            15,
            uuid,
            "Player"
        );

        Thread.sleep(
            20L
        );

        manager.endSession(
            uuid
        );

        assertFalse(
            manager.hasActiveSession(
                uuid
            )
        );

        assertTrue(
            manager.getPlaytimeMillis(
                uuid,
                15
            )
                >= 20L
        );
    }

    @Test
    void checkpointPersistsTimeWithoutEndingSession()
        throws Exception {

        Path dataFile =
            tempDirectory.resolve(
                "player-telemetry.yml"
            );

        UUID uuid =
            UUID.randomUUID();

        PlayerTelemetryManager manager =
            new PlayerTelemetryManager(
                dataFile
            );

        manager.beginSession(
            20,
            uuid,
            "Player"
        );

        Thread.sleep(
            25L
        );

        manager.checkpointActiveSessions();

        assertTrue(
            manager.hasActiveSession(
                uuid
            )
        );

        /*
         * A second manager simulates a process reading the
         * checkpointed file after a crash.
         */
        PlayerTelemetryManager recovered =
            new PlayerTelemetryManager(
                dataFile
            );

        recovered.load();

        assertTrue(
            recovered.getPlaytimeMillis(
                uuid,
                20
            )
                > 0L
        );

        manager.endSession(
            uuid
        );
    }

    @Test
    void endAttemptSessionsClosesOnlyMatchingAttempt()
        throws Exception {

        Path dataFile =
            tempDirectory.resolve(
                "player-telemetry.yml"
            );

        UUID playerOne =
            UUID.randomUUID();

        UUID playerTwo =
            UUID.randomUUID();

        PlayerTelemetryManager manager =
            new PlayerTelemetryManager(
                dataFile
            );

        manager.beginSession(
            30,
            playerOne,
            "PlayerOne"
        );

        manager.beginSession(
            31,
            playerTwo,
            "PlayerTwo"
        );

        Thread.sleep(
            20L
        );

        manager.endAttemptSessions(
            30
        );

        assertFalse(
            manager.hasActiveSession(
                playerOne
            )
        );

        assertTrue(
            manager.hasActiveSession(
                playerTwo
            )
        );

        assertTrue(
            manager.getPlaytimeMillis(
                playerOne,
                30
            )
                > 0L
        );

        manager.endSession(
            playerTwo
        );
    }

    @Test
    void closeAllSessionsPersistsEverything()
        throws Exception {

        Path dataFile =
            tempDirectory.resolve(
                "player-telemetry.yml"
            );

        UUID uuid =
            UUID.randomUUID();

        PlayerTelemetryManager manager =
            new PlayerTelemetryManager(
                dataFile
            );

        manager.beginSession(
            40,
            uuid,
            "Player"
        );

        Thread.sleep(
            25L
        );

        manager.closeAllSessions();

        assertFalse(
            manager.hasActiveSession(
                uuid
            )
        );

        PlayerTelemetryManager recovered =
            new PlayerTelemetryManager(
                dataFile
            );

        recovered.load();

        assertTrue(
            recovered.getPlaytimeMillis(
                uuid,
                40
            )
                > 0L
        );
    }

    @Test
    void latestPlayerNamePersists()
        throws Exception {

        Path dataFile =
            tempDirectory.resolve(
                "player-telemetry.yml"
            );

        UUID uuid =
            UUID.randomUUID();

        PlayerTelemetryManager manager =
            new PlayerTelemetryManager(
                dataFile
            );

        manager.beginSession(
            1,
            uuid,
            "OldName"
        );

        manager.endSession(
            uuid
        );

        manager.beginSession(
            2,
            uuid,
            "NewName"
        );

        manager.endSession(
            uuid
        );

        PlayerTelemetryManager recovered =
            new PlayerTelemetryManager(
                dataFile
            );

        recovered.load();

        assertEquals(
            "NewName",
            recovered
                .getLatestName(
                    uuid
                )
                .orElseThrow()
        );
    }
}