package dev.instigatehardcore.replay;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class ReplayTerrainTimelineTest {
    private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID OTHER_WORLD = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final ReplayBlockPosition SPOT = new ReplayBlockPosition(5, 64, 2);

    private static ReplayActorScene scene() {
        return scene(8);
    }

    private static ReplayActorScene scene(int duration) {
        ReplayActorSnapshot player = new ReplayActorSnapshot(
            SUBJECT, "PLAYER", "Victim", 5, 64, 2, 0, 0,
            false, false, false, 20, ReplayEquipment.empty());
        ReplayClip clip = new ReplayClip(8, SUBJECT, WORLD, List.of(
            new ReplayFrame(100, 8, WORLD, SUBJECT, List.of(player)),
            new ReplayFrame(102, 8, WORLD, SUBJECT, List.of(player)),
            new ReplayFrame(104, 8, WORLD, SUBJECT, List.of(player))));
        return new ReplayActorScene(new FrozenDeathReplay(
            clip, new ReplayDeathMoment(8, SUBJECT, WORLD, 104, 12345,
                5, 64, 2, 0, 0, "died", "FALL"), List.of()), duration);
    }

    @Test
    void restoresOriginalBlockAndReplaysTwoChangesInOrder() {
        ReplayTerrainTimeline timeline = new ReplayTerrainTimeline(scene(), List.of(
            new ReplayBlockChange(102, 8, WORLD, SPOT,
                "minecraft:stone", "minecraft:oak_planks"),
            new ReplayBlockChange(104, 8, WORLD, SPOT,
                "minecraft:oak_planks", "minecraft:air")));
        assertEquals("minecraft:stone", timeline.initialStates().get(SPOT));
        assertTrue(timeline.changesAt(0).isEmpty());
        assertEquals("minecraft:oak_planks", timeline.changesAt(6).getFirst().afterData());
        assertEquals("minecraft:air", timeline.changesAt(8).getFirst().afterData());
    }

    @Test
    void preservesMultipleChangesAtSameTick() {
        ReplayTerrainTimeline timeline = new ReplayTerrainTimeline(scene(), List.of(
            new ReplayBlockChange(102, 8, WORLD, SPOT,
                "minecraft:stone", "minecraft:dirt"),
            new ReplayBlockChange(102, 8, WORLD, SPOT,
                "minecraft:dirt", "minecraft:air")));
        assertEquals("minecraft:stone", timeline.initialStates().get(SPOT));
        assertEquals(List.of("minecraft:dirt", "minecraft:air"),
            timeline.changesAt(6).stream().map(ReplayBlockChange::afterData).toList());
    }

    @Test
    void fluidBucketFlowAndDrainReturnToAirWithoutPreExistingSource() {
        ReplayTerrainTimeline timeline = new ReplayTerrainTimeline(scene(), List.of(
            new ReplayBlockChange(100, 8, WORLD, SPOT,
                "minecraft:air", "minecraft:water[level=0]"),
            new ReplayBlockChange(102, 8, WORLD,
                new ReplayBlockPosition(6, 64, 2),
                "minecraft:air", "minecraft:water[level=5]"),
            new ReplayBlockChange(104, 8, WORLD, SPOT,
                "minecraft:water[level=0]", "minecraft:air"),
            new ReplayBlockChange(104, 8, WORLD,
                new ReplayBlockPosition(6, 64, 2),
                "minecraft:water[level=5]", "minecraft:air")));
        assertEquals("minecraft:air", timeline.initialStates().get(SPOT));
        assertEquals("minecraft:air", timeline.initialStates().get(
            new ReplayBlockPosition(6, 64, 2)));
        assertEquals("minecraft:water[level=0]",
            timeline.changesAt(4).getFirst().afterData());
        assertEquals("minecraft:air", timeline.changesAt(8).getFirst().afterData());
        assertEquals("minecraft:air", timeline.changesAt(8).get(1).afterData());
    }

    @Test
    void ignoresWrongDimensionAndAttempt() {
        ReplayTerrainTimeline timeline = new ReplayTerrainTimeline(scene(), List.of(
            new ReplayBlockChange(102, 9, WORLD, SPOT, "minecraft:stone", "minecraft:air"),
            new ReplayBlockChange(102, 8, OTHER_WORLD, SPOT, "minecraft:stone", "minecraft:air")));
        assertTrue(timeline.initialStates().isEmpty());
    }

    @Test
    void terminalEventsAreVisibleBeforeReplayEnds() {
        ReplayActorScene scene = scene(140);
        ReplayTerrainTimeline timeline = new ReplayTerrainTimeline(scene, List.of(
            new ReplayBlockChange(104, 8, WORLD, SPOT, "minecraft:stone", "minecraft:air")));
        assertEquals(1, timeline.changesAt(135).size());
    }

    @Test
    void modelsRejectInvalidData() {
        assertThrows(IllegalArgumentException.class, () ->
            new ReplayBlockChange(100, 8, WORLD, SPOT, "", "minecraft:air"));
        assertThrows(IllegalArgumentException.class, () ->
            new ReplayBlockChange(100, 0, WORLD, SPOT, "minecraft:stone", "minecraft:air"));
    }
}
