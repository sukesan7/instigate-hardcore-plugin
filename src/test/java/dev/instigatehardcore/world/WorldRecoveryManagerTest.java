package dev.instigatehardcore.world;

import dev.instigatehardcore.stats.StatsManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorldRecoveryManagerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void stableStateRequiresNoRecovery()
        throws IOException {

        StatsManager stats =
            createStatsManager();

        WorldStateStore store =
            createWorldStore();

        store.loadOrCreate(
            1
        );

        WorldRecoveryManager manager =
            new WorldRecoveryManager(
                stats,
                store
            );

        WorldRecoveryManager.RecoveryResult result =
            manager.recover();

        assertFalse(
            result.completedIncompleteRotation()
        );

        assertFalse(
            result.advancedStats()
        );

        assertEquals(
            1,
            result.state().activeAttempt()
        );

        assertEquals(
            WorldRotationPhase.STABLE,
            result.state().phase()
        );
    }

    @Test
    void rotatingStatePromotesStandbyAndAdvancesStats()
        throws IOException {

        StatsManager stats =
            createStatsManager();

        WorldStateStore store =
            createWorldStore();

        store.save(
            new WorldRotationState(
                1,
                111L,
                2,
                222L,
                WorldRotationPhase.ROTATING
            )
        );

        WorldRecoveryManager manager =
            new WorldRecoveryManager(
                stats,
                store
            );

        WorldRecoveryManager.RecoveryResult result =
            manager.recover();

        assertTrue(
            result.completedIncompleteRotation()
        );

        assertTrue(
            result.advancedStats()
        );

        assertEquals(
            2,
            stats.getCurrentAttempt()
        );

        assertEquals(
            2,
            result.state().activeAttempt()
        );

        assertEquals(
            222L,
            result.state().activeSeed()
        );

        assertEquals(
            3,
            result.state().standbyAttempt()
        );

        assertEquals(
            WorldRotationPhase.STABLE,
            result.state().phase()
        );
    }

    @Test
    void promotedWorldStateCanRepairLaggingStats()
        throws IOException {

        StatsManager stats =
            createStatsManager();

        WorldStateStore store =
            createWorldStore();

        /*
         * Simulates:
         *
         * promoteStandby() succeeded
         * statsManager.advanceAttempt() never ran
         */
        store.save(
            new WorldRotationState(
                2,
                222L,
                3,
                333L,
                WorldRotationPhase.STABLE
            )
        );

        WorldRecoveryManager manager =
            new WorldRecoveryManager(
                stats,
                store
            );

        WorldRecoveryManager.RecoveryResult result =
            manager.recover();

        assertFalse(
            result.completedIncompleteRotation()
        );

        assertTrue(
            result.advancedStats()
        );

        assertEquals(
            2,
            stats.getCurrentAttempt()
        );

        assertEquals(
            2,
            result.state().activeAttempt()
        );
    }

    private StatsManager createStatsManager()
        throws IOException {

        StatsManager manager =
            new StatsManager(
                temporaryDirectory.resolve(
                    "stats.properties"
                )
            );

        manager.load();

        return manager;
    }

    private WorldStateStore createWorldStore() {
        return new WorldStateStore(
            temporaryDirectory.resolve(
                "world-state.properties"
            )
        );
    }
}