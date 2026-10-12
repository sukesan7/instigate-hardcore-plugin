package dev.instigatehardcore.replay;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ReplayConsumptionAudioTest {
    @Test void edibleMapsToBites() {
        assertEquals(ReplayVisualEvent.Kind.EAT_BITE,
            ReplayConsumptionAudio.kindFor("COOKED_BEEF", true));
    }
    @Test void potionsAndMilkMapToSips() {
        assertEquals(ReplayVisualEvent.Kind.DRINK_SIP,
            ReplayConsumptionAudio.kindFor("POTION", false));
        assertEquals(ReplayVisualEvent.Kind.DRINK_SIP,
            ReplayConsumptionAudio.kindFor("MILK_BUCKET", false));
    }
    @Test void bitesAreOrderedAndNeverNegative() {
        assertEquals(java.util.List.of(76L, 82L, 88L, 94L, 98L),
            ReplayConsumptionAudio.biteTicks(100));
        assertEquals(java.util.List.of(0L), ReplayConsumptionAudio.biteTicks(2));
    }
}
