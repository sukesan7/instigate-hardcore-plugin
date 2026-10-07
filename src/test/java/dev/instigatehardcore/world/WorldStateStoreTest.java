package dev.instigatehardcore.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorldStateStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void freshStateUsesCurrentAndNextAttempt()
        throws IOException {

        WorldStateStore store =
            createStore();

        WorldRotationState state =
            store.loadOrCreate(7);

        assertEquals(
            7,
            state.activeAttempt()
        );

        assertEquals(
            8,
            state.standbyAttempt()
        );
    }

    @Test
    void statePersistsAcrossReload()
        throws IOException {

        Path file =
            temporaryDirectory.resolve(
                "world-state.properties"
            );

        WorldStateStore firstStore =
            new WorldStateStore(file);

        WorldRotationState original =
            firstStore.loadOrCreate(12);

        WorldStateStore secondStore =
            new WorldStateStore(file);

        WorldRotationState loaded =
            secondStore.load();

        assertEquals(
            original,
            loaded
        );
    }

    @Test
    void seedsRemainStableAcrossReload()
        throws IOException {

        WorldStateStore store =
            createStore();

        WorldRotationState first =
            store.loadOrCreate(3);

        WorldRotationState second =
            store.loadOrCreate(3);

        assertEquals(
            first.activeSeed(),
            second.activeSeed()
        );

        assertEquals(
            first.standbySeed(),
            second.standbySeed()
        );
    }

    @Test
    void standbyAttemptMustFollowActiveAttempt() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new WorldRotationState(
                5,
                123L,
                9,
                456L
            )
        );
    }

    private WorldStateStore createStore() {
        return new WorldStateStore(
            temporaryDirectory.resolve(
                "world-state.properties"
            )
        );
    }
}