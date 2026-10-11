package dev.instigatehardcore.replay;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReplayEntityMotionTest {
    private static ReplayEntityMotion.Plan plan(
        double x, double y, double z, double nextX, double nextY, double nextZ
    ) {
        return ReplayEntityMotion.plan(x,y,z,nextX,nextY,nextZ,0,0,0,0);
    }

    @Test void stationaryActorsDoNotGenerateMovementPackets() {
        assertEquals(ReplayEntityMotion.Kind.NONE,
            plan(3, 64, 8, 3, 64, 8).kind());
    }

    @Test void continuousMotionUsesRelativePackets() {
        var motion = plan(3, 64, 8, 3.2, 64.1, 8.4);
        assertEquals(ReplayEntityMotion.Kind.RELATIVE, motion.kind());
        assertEquals(0.2, motion.dx(), 0.000001);
        assertEquals(0.1, motion.dy(), 0.000001);
        assertEquals(0.4, motion.dz(), 0.000001);
    }

    @Test void rotatingInPlaceStillSendsRotation() {
        var motion = ReplayEntityMotion.plan(0,64,0,0,64,0,10,0,25,0);
        assertEquals(ReplayEntityMotion.Kind.RELATIVE, motion.kind());
    }

    @Test void edgeOfSafeRelativeRangeAllowed() {
        assertEquals(ReplayEntityMotion.Kind.RELATIVE,
            plan(0, 64, 0, 7, 57, -7).kind());
    }

    @Test void discontinuitiesUseAbsoluteTeleport() {
        assertEquals(ReplayEntityMotion.Kind.TELEPORT,
            plan(0, 64, 0, 8, 64, 0).kind());
        assertEquals(ReplayEntityMotion.Kind.TELEPORT,
            plan(0, 64, 0, 0, 56.9, 0).kind());
    }

    @Test void limitAppliesToEachAxis() {
        assertEquals(ReplayEntityMotion.Kind.RELATIVE,
            plan(0,0,0, 6.5,6.5,6.5).kind());
        assertEquals(ReplayEntityMotion.Kind.TELEPORT,
            plan(0,0,0, 6.5,7.01,6.5).kind());
    }

    @Test void nonFiniteInputsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
            plan(0,0,0,Double.NaN,1,2));
        assertThrows(IllegalArgumentException.class, () ->
            ReplayEntityMotion.plan(0,0,0,0,0,0,0,0,Float.NaN,0));
    }

    @Test void encodedPositionMustBeUsedAsNextOrigin() {
        // Simulates a relative delta quantized to a smaller value on wire.
        // The next packet must include the untransmitted remainder.
        double encodedX = 0.199951171875;
        var next = plan(encodedX, 0, 0, 0.4, 0, 0);
        assertEquals(ReplayEntityMotion.Kind.RELATIVE, next.kind());
        assertEquals(0.4 - encodedX, next.dx(), 1e-10);
    }
}
