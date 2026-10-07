package dev.instigatehardcore.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RunManagerTest {

    @Test
    void newManagerStartsInStartingState() {
        RunManager manager = new RunManager();

        assertEquals(RunState.STARTING, manager.getState());
        assertFalse(manager.isActive());
    }

    @Test
    void startRunTransitionsToActive() {
        RunManager manager = new RunManager();

        assertTrue(manager.startRun());

        assertEquals(RunState.ACTIVE, manager.getState());
        assertTrue(manager.isActive());
        assertNotNull(manager.getStartedAt());
    }

    @Test
    void runCannotBeStartedTwice() {
        RunManager manager = new RunManager();

        assertTrue(manager.startRun());
        assertFalse(manager.startRun());

        assertEquals(RunState.ACTIVE, manager.getState());
    }

    @Test
    void onlyFirstEndRequestSucceeds() {
        RunManager manager = new RunManager();

        manager.startRun();

        assertTrue(manager.beginEnding());
        assertFalse(manager.beginEnding());

        assertEquals(RunState.ENDING, manager.getState());
    }

    @Test
    void cannotResetBeforeRunEnds() {
        RunManager manager = new RunManager();

        manager.startRun();

        assertFalse(manager.beginResetting());
        assertEquals(RunState.ACTIVE, manager.getState());
    }

    @Test
    void endingRunCanTransitionToResetting() {
        RunManager manager = new RunManager();

        manager.startRun();
        manager.beginEnding();

        assertTrue(manager.beginResetting());

        assertEquals(RunState.RESETTING, manager.getState());
        assertTrue(manager.isResetting());
    }

    @Test
    void endedRunReportsAsEnded() {
        RunManager manager = new RunManager();

        manager.startRun();

        assertFalse(manager.hasEnded());

        manager.beginEnding();

        assertTrue(manager.hasEnded());

        manager.beginResetting();

        assertTrue(manager.hasEnded());
    }
}