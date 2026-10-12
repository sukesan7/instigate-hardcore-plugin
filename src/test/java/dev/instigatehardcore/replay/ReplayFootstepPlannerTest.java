package dev.instigatehardcore.replay;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class ReplayFootstepPlannerTest {
    private static final UUID ID = UUID.fromString("7fd5e1f4-a91a-44ad-8b78-00e91c2d8000");

    private static ReplayActorPose pose(double x, boolean sneak, boolean ground, String material) {
        return new ReplayActorPose(
            ID, "PLAYER", "survivor", x, 65, 0, 0, 0, sneak, false,
            false, 20, ReplayEquipment.empty(), false, ground, material,
            ReplayCreeperState.NONE);
    }

    @Test void standingStillIsSilent() {
        ReplayFootstepPlanner p = new ReplayFootstepPlanner(8);
        for (int t = 0; t < 20; t++)
            assertTrue(p.advance(t, Map.of(ID, pose(0, false, true, "STONE"))).isEmpty());
    }

    @Test void walkingMakesFootstepsAfterMotion() {
        ReplayFootstepPlanner p = new ReplayFootstepPlanner(8);
        int heard = 0;
        for (int t = 0; t < 20; t++)
            heard += p.advance(t, Map.of(ID, pose(t * 0.2, false, true, "STONE"))).size();
        assertTrue(heard >= 2);
    }

    @Test void sneakingStillProducesQuietCadencedSteps() {
        ReplayFootstepPlanner p = new ReplayFootstepPlanner(8);
        int heard = 0;
        for (int t = 0; t < 30; t++)
            heard += p.advance(t, Map.of(ID, pose(t * 0.07, true, true, "GRASS_BLOCK"))).size();
        assertTrue(heard >= 2);
    }

    @Test void airborneAndFluidProduceNoFootsteps() {
        ReplayFootstepPlanner p = new ReplayFootstepPlanner(8);
        for (int t = 0; t < 20; t++)
            assertTrue(p.advance(t, Map.of(ID, pose(t * 0.4, false, false, "AIR"))).isEmpty());
        for (int t = 20; t < 40; t++)
            assertTrue(p.advance(t, Map.of(ID, pose(t * 0.4, false, true, "WATER"))).isEmpty());
    }

    @Test void largeRosterStillHonorsTickBudget() {
        ReplayFootstepPlanner p = new ReplayFootstepPlanner(2);
        java.util.Map<UUID, ReplayActorPose> actors = new java.util.LinkedHashMap<>();
        for (int t = 0; t < 7; t++) {
            actors.clear();
            for (int i = 0; i < 12; i++) {
                UUID id = new UUID(0, i + 1);
                actors.put(id, new ReplayActorPose(id, "PLAYER", "p" + i,
                    t * 0.2, 65, 0, 0, 0, false, false, false, 20,
                    ReplayEquipment.empty(), false, true, "STONE", ReplayCreeperState.NONE));
            }
            assertTrue(p.advance(t, actors).size() <= 2);
        }
    }
}
