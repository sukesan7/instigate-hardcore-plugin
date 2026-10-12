package dev.instigatehardcore.replay;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

final class ReplayTerminalExplosionWindowTest {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000004");

    @Test
    void acceptsOnlyTheDeathTickOrTheNextTickInTheSameAttemptAndWorld() {
        ReplayTerminalExplosionWindow window = new ReplayTerminalExplosionWindow(
            40, WORLD, 1000, 0, 64, 0, 24);
        assertTrue(window.accepts(40, WORLD, 1000, 1, 64, 1));
        assertTrue(window.accepts(40, WORLD, 1001, 1, 64, 1));
        assertFalse(window.accepts(40, WORLD, 1002, 1, 64, 1));
        assertFalse(window.accepts(40, WORLD, 999, 1, 64, 1));
        assertFalse(window.accepts(39, WORLD, 1000, 1, 64, 1));
        assertFalse(window.accepts(40, UUID.randomUUID(), 1000, 1, 64, 1));
        assertFalse(window.accepts(40, WORLD, 1000, 50, 64, 0));
    }

    @Test
    void rejectsBadWindowConfiguration() {
        assertThrows(IllegalArgumentException.class, () ->
            new ReplayTerminalExplosionWindow(0, WORLD, 100, 0, 0, 0, 24));
    }
}
