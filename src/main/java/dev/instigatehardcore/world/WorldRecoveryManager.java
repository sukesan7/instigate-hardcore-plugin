package dev.instigatehardcore.world;

import dev.instigatehardcore.stats.StatsManager;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

public final class WorldRecoveryManager {

    private final StatsManager statsManager;
    private final WorldStateStore stateStore;

    public WorldRecoveryManager(
        StatsManager statsManager,
        WorldStateStore stateStore
    ) {
        this.statsManager =
            Objects.requireNonNull(
                statsManager
            );

        this.stateStore =
            Objects.requireNonNull(
                stateStore
            );
    }

    /**
     * Reconciles persistent world state and persistent campaign
     * statistics before any attempt worlds are loaded.
     */
    public RecoveryResult recover()
        throws IOException {

        int statsAttempt =
            statsManager
                .getCurrentAttempt();

        WorldRotationState state =
            stateStore.loadOrCreate(
                statsAttempt
            );

        boolean completedIncompleteRotation =
            false;

        boolean advancedStats =
            false;

        /*
         * A ROTATING marker means the old attempt had already
         * ended and the standby attempt was committed as the next
         * attempt, even if Paper crashed before promotion finished.
         */
        if (
            state.phase()
                == WorldRotationPhase.ROTATING
        ) {
            /*
             * Normally stats will still reference the old active
             * attempt.
             *
             * We also accept stats already referencing the standby
             * attempt so recovery remains safe if operation ordering
             * changes in the future.
             */
            if (
                statsAttempt
                    != state.activeAttempt()
                && statsAttempt
                    != state.standbyAttempt()
            ) {
                throw new IOException(
                    "Cannot recover ROTATING world state. "
                        + "Stats attempt is #"
                        + statsAttempt
                        + ", persistent active is #"
                        + state.activeAttempt()
                        + ", and persistent standby is #"
                        + state.standbyAttempt()
                        + "."
                );
            }

            state =
                completeInterruptedRotation(
                    state
                );

            completedIncompleteRotation =
                true;
        }

        /*
         * After the world state has been reconciled, it becomes
         * the authoritative record of which attempt should exist.
         */
        statsAttempt =
            statsManager
                .getCurrentAttempt();

        if (
            state.activeAttempt()
                == statsAttempt + 1
        ) {
            int advancedAttempt =
                statsManager
                    .advanceAttempt();

            if (
                advancedAttempt
                    != state.activeAttempt()
            ) {
                throw new IOException(
                    "Failed to reconcile persistent attempt number. "
                        + "Expected #"
                        + state.activeAttempt()
                        + " but StatsManager advanced to #"
                        + advancedAttempt
                        + "."
                );
            }

            advancedStats =
                true;
        } else if (
            state.activeAttempt()
                != statsAttempt
        ) {
            throw new IOException(
                "World state and statistics cannot be reconciled. "
                    + "Stats attempt is #"
                    + statsAttempt
                    + " but active world attempt is #"
                    + state.activeAttempt()
                    + "."
            );
        }

        if (
            state.phase()
                != WorldRotationPhase.STABLE
        ) {
            throw new IOException(
                "World recovery finished with a non-STABLE pipeline."
            );
        }

        if (
            state.standbyAttempt()
                != state.activeAttempt() + 1
        ) {
            throw new IOException(
                "Recovered standby attempt does not follow "
                    + "the active attempt."
            );
        }

        return new RecoveryResult(
            state,
            completedIncompleteRotation,
            advancedStats
        );
    }

    private WorldRotationState completeInterruptedRotation(
        WorldRotationState interrupted
    ) throws IOException {

        int promotedAttempt =
            interrupted.standbyAttempt();

        long promotedSeed =
            interrupted.standbySeed();

        int replacementStandbyAttempt =
            promotedAttempt + 1;

        long replacementStandbySeed =
            generateSeedDifferentFrom(
                promotedSeed
            );

        WorldRotationState recovered =
            new WorldRotationState(
                promotedAttempt,
                promotedSeed,
                replacementStandbyAttempt,
                replacementStandbySeed,
                WorldRotationPhase.STABLE
            );

        /*
         * Persist the recovered pipeline before touching stats.
         *
         * If the process crashes again immediately afterward,
         * startup can simply reconcile stats against this state.
         */
        stateStore.save(
            recovered
        );

        return recovered;
    }

    private long generateSeedDifferentFrom(
        long otherSeed
    ) {
        long seed;

        do {
            seed =
                ThreadLocalRandom
                    .current()
                    .nextLong();
        } while (
            seed == otherSeed
        );

        return seed;
    }

    public record RecoveryResult(
        WorldRotationState state,
        boolean completedIncompleteRotation,
        boolean advancedStats
    ) {
    }
}