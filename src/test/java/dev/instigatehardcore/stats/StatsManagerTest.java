package dev.instigatehardcore.stats;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StatsManagerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void freshCampaignStartsAtAttemptOne() throws IOException {
        StatsManager manager = createManager();

        assertEquals(1, manager.getCurrentAttempt());
    }

    @Test
    void recordingDeathIncrementsPlayerDeaths() throws IOException {
        StatsManager manager = createManager();

        UUID playerId = UUID.randomUUID();

        manager.recordDeath(playerId, "Sukesan");

        assertEquals(
            1,
            manager.getDeaths(playerId)
        );

        manager.recordDeath(playerId, "Sukesan");

        assertEquals(
            2,
            manager.getDeaths(playerId)
        );
    }

    @Test
    void deathsPersistAcrossReload() throws IOException {
        Path file = temporaryDirectory.resolve("stats.properties");

        UUID playerId = UUID.randomUUID();

        StatsManager firstManager = new StatsManager(file);
        firstManager.load();

        firstManager.recordDeath(
            playerId,
            "Sukesan"
        );

        StatsManager secondManager = new StatsManager(file);
        secondManager.load();

        assertEquals(
            1,
            secondManager.getDeaths(playerId)
        );
    }

    @Test
    void attemptNumberPersistsAcrossReload() throws IOException {
        Path file = temporaryDirectory.resolve("stats.properties");

        StatsManager firstManager = new StatsManager(file);
        firstManager.load();

        assertEquals(
            2,
            firstManager.advanceAttempt()
        );

        StatsManager secondManager = new StatsManager(file);
        secondManager.load();

        assertEquals(
            2,
            secondManager.getCurrentAttempt()
        );
    }

    @Test
    void playersAreSortedByDeaths() throws IOException {
        StatsManager manager = createManager();

        UUID playerOne = UUID.randomUUID();
        UUID playerTwo = UUID.randomUUID();

        manager.recordDeath(
            playerOne,
            "PlayerOne"
        );

        manager.recordDeath(
            playerTwo,
            "PlayerTwo"
        );

        manager.recordDeath(
            playerTwo,
            "PlayerTwo"
        );

        assertEquals(
            "PlayerTwo",
            manager.getPlayersByDeaths().getFirst().name()
        );

        assertEquals(
            2,
            manager.getPlayersByDeaths().getFirst().deaths()
        );
    }

    @Test
    void newPlayerIsRegisteredWithZeroDeaths() throws IOException {
        StatsManager manager = createManager();

        UUID playerId = UUID.randomUUID();

        PlayerStats player = manager.ensurePlayer(
            playerId,
            "Sukesan"
        );

        assertEquals("Sukesan", player.name());
        assertEquals(0, player.deaths());
        assertEquals(0, manager.getDeaths(playerId));
    }

    @Test
    void playerNameCanChangeWithoutResettingDeaths() throws IOException {
        StatsManager manager = createManager();

        UUID playerId = UUID.randomUUID();

        manager.recordDeath(
            playerId,
            "OldName"
        );

        PlayerStats updated = manager.ensurePlayer(
            playerId,
            "NewName"
        );

        assertEquals("NewName", updated.name());
        assertEquals(1, updated.deaths());
    }

    private StatsManager createManager() throws IOException {
        Path file = temporaryDirectory.resolve("stats.properties");

        StatsManager manager = new StatsManager(file);
        manager.load();

        return manager;
    }
}