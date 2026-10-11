package dev.instigatehardcore.replay;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReplayCinematicCameraMotionTest {
    private static ReplayCinematicCameraPath.Frame frame(double x, double y, double z) {
        return new ReplayCinematicCameraPath.Frame(x, y, z, 0, 0);
    }

    @Test
    void ordinaryOneTickCameraTravelUsesSmoothRelativeMove() {
        assertTrue(ReplayCinematicCameraMotion.useRelativeMove(
            frame(3, 64, 10), frame(3.4, 64.1, 10.3)));
    }

    @Test
    void turningInPlaceStillUsesRelativePacketForRotation() {
        var before = frame(3, 64, 10);
        var after = new ReplayCinematicCameraPath.Frame(3, 64, 10, 120, 30);
        assertTrue(ReplayCinematicCameraMotion.useRelativeMove(before, after));
    }

    @Test
    void suddenPositionDiscontinuityUsesAbsoluteSync() {
        assertFalse(ReplayCinematicCameraMotion.useRelativeMove(
            frame(0, 64, 0), frame(10, 64, 0)));
    }

    @Test
    void maximumSafeDeltaIsAllowed() {
        assertTrue(ReplayCinematicCameraMotion.useRelativeMove(
            frame(0, 64, 0), frame(7, 57, -7)));
    }

    @Test
    void eachAxisHasIndependentRelativeLimit() {
        assertFalse(ReplayCinematicCameraMotion.useRelativeMove(
            frame(0, 64, 0), frame(0, 71.01, 0)));
    }

    @Test
    void missingPriorOrNextFrameUsesAbsoluteSync() {
        assertFalse(ReplayCinematicCameraMotion.useRelativeMove(null, frame(0, 0, 0)));
        assertFalse(ReplayCinematicCameraMotion.useRelativeMove(frame(0, 0, 0), null));
    }
}
