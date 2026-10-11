package dev.instigatehardcore.replay;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReplayCinematicCameraPathTest {
    @Test
    void followsBehindVictimFacingSouth() {
        var camera = ReplayCinematicCameraPath.desired(10, 64, 20, 0f, 4, 2, 0);
        assertEquals(10, camera.x(), 1.0e-6);
        assertEquals(66, camera.y(), 1.0e-6);
        assertEquals(16, camera.z(), 1.0e-6);
        assertEquals(0, camera.yaw(), 1.0e-5);
        assertTrue(camera.pitch() > 0); // Looks slightly down toward the victim.
    }

    @Test
    void followsBehindVictimFacingNorth() {
        var camera = ReplayCinematicCameraPath.desired(1, 64, 2, 180f, 4, 2, 0);
        assertEquals(6, camera.z(), 1.0e-6);
        assertEquals(180, Math.abs(camera.yaw()), 1.0e-4);
    }

    @Test
    void shoulderOffsetUsesVictimRightSide() {
        var camera = ReplayCinematicCameraPath.desired(0, 64, 0, 0f, 4, 2, 1);
        assertEquals(1, camera.x(), 1.0e-6);
        assertEquals(-4, camera.z(), 1.0e-6);
        assertTrue(camera.yaw() > 0);
    }

    @Test
    void wrapsYawAlongShortestArc() {
        var start = new ReplayCinematicCameraPath.Frame(0, 64, 0, 179, 5);
        var end = new ReplayCinematicCameraPath.Frame(4, 64, 0, -179, 5);
        var frame = ReplayCinematicCameraPath.smooth(start, end, 0.5);
        assertEquals(2, frame.x(), 1.0e-6);
        assertEquals(180, frame.yaw(), 0.01);
    }

    @Test
    void snapsOverVeryLargeDisplacements() {
        var before = new ReplayCinematicCameraPath.Frame(0, 64, 0, 90, 0);
        var after = new ReplayCinematicCameraPath.Frame(100, 64, 0, 90, 0);
        assertSame(after, ReplayCinematicCameraPath.smooth(before, after, 0.25));
    }

    @Test
    void rotatesCameraBehindVictimAfterQuarterTurn() {
        var south = ReplayCinematicCameraPath.desired(0, 64, 0, 0f, 4, 2, 0);
        var west = ReplayCinematicCameraPath.desired(0, 64, 0, 90f, 4, 2, 0);
        assertEquals(-4.0, south.z(), 1.0e-6);
        assertEquals(4.0, west.x(), 1.0e-6);
        assertEquals(0f, south.yaw(), 1.0e-4);
        assertEquals(90f, west.yaw(), 1.0e-4);
    }

    @Test
    void smoothCameraAlsoRotatesTowardVictimAfterTurn() {
        var south = ReplayCinematicCameraPath.desired(0, 64, 0, 0f, 4, 2, 0);
        var west = ReplayCinematicCameraPath.desired(0, 64, 0, 90f, 4, 2, 0);
        var next = ReplayCinematicCameraPath.smooth(south, west, 0.25);
        assertEquals(22.5f, next.yaw(), 1.0e-4);
        assertNotEquals(south.yaw(), next.yaw());
    }

    @Test
    void rejectedInvalidCoordinatesAndConfig() {
        assertThrows(IllegalArgumentException.class, () ->
            ReplayCinematicCameraPath.desired(0, 64, 0, 0, 0, 2, 0));
        assertThrows(IllegalArgumentException.class, () ->
            new ReplayCinematicCameraPath.Frame(Double.NaN, 64, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () ->
            ReplayCinematicCameraPath.smooth(null,
                new ReplayCinematicCameraPath.Frame(0, 0, 0, 0, 0), 0));
    }
}
