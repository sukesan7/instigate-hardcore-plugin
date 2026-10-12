package dev.instigatehardcore.replay;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReplayFireVisualStateTest {
    @Test void immediateLavaAndFireContactIgnitesGhost() {
        assertTrue(ReplayFireVisualState.isBurning(false, "LAVA", "AIR"));
        assertTrue(ReplayFireVisualState.isBurning(false, "AIR", "FIRE"));
        assertTrue(ReplayFireVisualState.isBurning(false, "SOUL_FIRE", "AIR"));
    }

    @Test void persistsAfterContactUntilVanillaFireTicksExpire() {
        assertTrue(ReplayFireVisualState.isBurning(true, "AIR", "AIR"));
        assertFalse(ReplayFireVisualState.isBurning(false, "AIR", "WATER"));
    }
}
