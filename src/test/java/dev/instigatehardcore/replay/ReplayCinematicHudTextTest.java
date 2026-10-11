package dev.instigatehardcore.replay;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReplayCinematicHudTextTest {
    @Test
    void sevenSecondClockCountsDownWithoutPrematureZero() {
        assertEquals("00:07", ReplayCinematicHudText.clock(0, 140));
        assertEquals("00:07", ReplayCinematicHudText.clock(1, 140));
        assertEquals("00:06", ReplayCinematicHudText.clock(20, 140));
        assertEquals("00:01", ReplayCinematicHudText.clock(139, 140));
        assertEquals("00:00", ReplayCinematicHudText.clock(140, 140));
    }

    @Test
    void neverDisplaysNegativeSeconds() {
        assertEquals(0, ReplayCinematicHudText.secondsLeft(200, 140));
        assertEquals(7, ReplayCinematicHudText.secondsLeft(-5, 140));
        assertThrows(IllegalArgumentException.class, () ->
            ReplayCinematicHudText.clock(0, 0));
    }

    @Test
    void progressStartsFullAndEndsEmpty() {
        assertEquals(1f, ReplayCinematicHudText.fractionRemaining(0, 140));
        assertEquals(0.5f, ReplayCinematicHudText.fractionRemaining(70, 140));
        assertEquals(0f, ReplayCinematicHudText.fractionRemaining(140, 140));
        assertEquals(0f, ReplayCinematicHudText.fractionRemaining(160, 140));
    }

    @Test
    void sanitizesNamesUsedInHud() {
        assertEquals("Unknown Player", ReplayCinematicHudText.cleanName("  "));
        assertEquals("scarbz", ReplayCinematicHudText.cleanName("scarbz"));
        assertEquals("AB", ReplayCinematicHudText.cleanName("A§B"));
        assertEquals(32, ReplayCinematicHudText.cleanName("x".repeat(80)).length());
    }
}
