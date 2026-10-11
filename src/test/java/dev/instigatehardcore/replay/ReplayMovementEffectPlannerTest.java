package dev.instigatehardcore.replay;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class ReplayMovementEffectPlannerTest {
    private final UUID id = UUID.randomUUID();

    private ReplayActorPose pose(UUID actor, String type, double x, double y,
                                 boolean sprint, boolean grounded, String block) {
        return new ReplayActorPose(actor, type, "Actor", x, y, 0, 0, 0,
            false, false, false, 20, ReplayEquipment.empty(),
            sprint, grounded, block);
    }

    private ReplayActorPose player(double x, double y, boolean sprint, boolean ground) {
        return pose(id, "PLAYER", x, y, sprint, ground, "GRASS_BLOCK");
    }

    @Test
    void sprintingWhileMovingProducesDustAtLimitedIntervals() {
        var planner = new ReplayMovementEffectPlanner(24);
        assertTrue(planner.advance(0, Map.of(id, player(0, 64, true, true))).isEmpty());
        for (int tick = 1; tick < 4; tick++) {
            assertTrue(planner.advance(tick, Map.of(id, player(tick * .25, 64, true, true))).isEmpty());
        }
        var effects = planner.advance(4, Map.of(id, player(1.0, 64, true, true)));
        assertEquals(1, effects.size());
        assertEquals(ReplayMovementEffect.Kind.SPRINT_DUST, effects.getFirst().kind());
        assertEquals(3, effects.getFirst().particleCount());
    }

    @Test
    void jumpingAndLandingCreatesOneLandingBurst() {
        var planner = new ReplayMovementEffectPlanner(24);
        planner.advance(0, Map.of(id, player(0, 64, false, true)));
        planner.advance(1, Map.of(id, player(0, 64.4, false, false)));
        planner.advance(2, Map.of(id, player(0, 64.7, false, false)));
        planner.advance(3, Map.of(id, player(0, 64.2, false, false)));
        var effects = planner.advance(4, Map.of(id, player(0, 64, false, true)));
        assertEquals(1, effects.size());
        assertEquals(ReplayMovementEffect.Kind.LANDING_DUST, effects.getFirst().kind());
        assertTrue(planner.advance(5, Map.of(id, player(0, 64, false, true))).isEmpty());
    }

    @Test
    void walkingStillOrBeingAirborneSuppressesSprintDust() {
        var planner = new ReplayMovementEffectPlanner(24);
        for (int tick = 0; tick <= 8; tick++) {
            assertTrue(planner.advance(tick, Map.of(id, player(0, 64, true, true))).isEmpty());
        }
        var walk = new ReplayMovementEffectPlanner(24);
        for (int tick = 0; tick <= 8; tick++) {
            assertTrue(walk.advance(tick, Map.of(id, player(tick, 64, false, true))).isEmpty());
        }
        var airborne = new ReplayMovementEffectPlanner(24);
        for (int tick = 0; tick <= 8; tick++) {
            assertTrue(airborne.advance(tick, Map.of(id, player(tick, 66, true, false))).isEmpty());
        }
    }

    @Test
    void ignoresMobMovementAndAirBlockMaterials() {
        var planner = new ReplayMovementEffectPlanner(24);
        planner.advance(0, Map.of(id, pose(id, "ZOMBIE", 0, 64, true, true, "STONE")));
        assertTrue(planner.advance(4, Map.of(id, pose(id, "ZOMBIE", 1, 64, true, true, "STONE"))).isEmpty());
        var air = new ReplayMovementEffectPlanner(24);
        air.advance(0, Map.of(id, pose(id, "PLAYER", 0, 64, true, true, "AIR")));
        assertTrue(air.advance(4, Map.of(id, pose(id, "PLAYER", 1, 64, true, true, "AIR"))).isEmpty());
    }

    @Test
    void capsTotalParticlesAcrossCrowdedScenes() {
        var planner = new ReplayMovementEffectPlanner(8);
        Map<UUID, ReplayActorPose> start = new LinkedHashMap<>();
        Map<UUID, ReplayActorPose> moved = new LinkedHashMap<>();
        for (int i = 0; i < 12; i++) {
            UUID actor = UUID.nameUUIDFromBytes(("p" + i).getBytes());
            start.put(actor, pose(actor, "PLAYER", 0, 64, true, true, "STONE"));
            moved.put(actor, pose(actor, "PLAYER", 2, 64, true, true, "STONE"));
        }
        planner.advance(0, start);
        var effects = planner.advance(4, moved);
        assertEquals(8, effects.stream().mapToInt(ReplayMovementEffect::particleCount).sum());
    }

    @Test
    void removedAndReappearingPlayersDoNotCreateFalseLandingBursts() {
        var planner = new ReplayMovementEffectPlanner(24);
        planner.advance(0, Map.of(id, player(0, 70, false, false)));
        planner.advance(4, Map.of());
        assertTrue(planner.advance(8, Map.of(id, player(0, 64, false, true))).isEmpty());
    }

    @Test
    void preservesSnapshotMovementFlagsThroughInterpolation() {
        var equipment = ReplayEquipment.empty();
        var left = new ReplayActorSnapshot(id, "PLAYER", "Test", 0, 64, 0,
            0, 0, false, false, false, 20, equipment, true, false, "STONE");
        var right = new ReplayActorSnapshot(id, "PLAYER", "Test", 2, 64, 0,
            0, 0, false, false, false, 20, equipment, false, true, "GRASS_BLOCK");
        var before = ReplayActorPose.interpolate(left, right, .5);
        assertTrue(before.sprinting());
        assertFalse(before.onGround());
        assertEquals("STONE", before.groundMaterial());
        var after = ReplayActorPose.interpolate(left, right, 1.0);
        assertFalse(after.sprinting());
        assertTrue(after.onGround());
        assertEquals("GRASS_BLOCK", after.groundMaterial());
    }

    @Test
    void oldSnapshotConstructorsRemainSupported() {
        var old = new ReplayActorSnapshot(id, "PLAYER", "old", 0, 64, 0,
            0, 0, false, false, false, 20, ReplayEquipment.empty());
        assertEquals("AIR", old.groundMaterial());
        assertFalse(ReplayActorPose.from(old).sprinting());
    }

    @Test
    void rejectsInvalidTickSequenceAndBudget() {
        assertThrows(IllegalArgumentException.class, () -> new ReplayMovementEffectPlanner(0));
        var planner = new ReplayMovementEffectPlanner(24);
        planner.advance(1, Map.of());
        assertThrows(IllegalArgumentException.class, () -> planner.advance(1, Map.of()));
    }
}
