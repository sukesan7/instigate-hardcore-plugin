package dev.instigatehardcore.replay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReplayCameraHeadRotationTest {
    @Test void stationaryCameraNeedsNoAdditionalHeadPacket() {
        assertFalse(ReplayCameraHeadRotation.changed(45f, 45f));
    }

    @Test void turningInPlaceRequiresHeadPacket() {
        assertTrue(ReplayCameraHeadRotation.changed(45f, 90f));
    }

    @Test void wraparoundYawStillUpdates() {
        assertTrue(ReplayCameraHeadRotation.changed(179f, -179f));
    }

    @Test void rejectsNonFiniteAngles() {
        assertThrows(IllegalArgumentException.class,
            () -> ReplayCameraHeadRotation.changed(Float.NaN, 0));
        assertThrows(IllegalArgumentException.class,
            () -> ReplayCameraHeadRotation.changed(0, Float.POSITIVE_INFINITY));
    }
}
