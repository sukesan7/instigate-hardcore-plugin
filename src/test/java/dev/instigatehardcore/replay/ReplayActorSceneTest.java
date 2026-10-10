package dev.instigatehardcore.replay;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class ReplayActorSceneTest {
    private static final UUID VICTIM = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ZOMBIE = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ARROW = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000004");

    private static ReplayActorSnapshot actor(UUID id, String type, double x, float yaw) {
        return new ReplayActorSnapshot(id, type, type, x, 64, 3, yaw, 0,
            false, false, false, 20, ReplayEquipment.empty());
    }

    private static FrozenDeathReplay capture() {
        List<ReplayFrame> frames = List.of(
            new ReplayFrame(10, 8, WORLD, VICTIM, List.of(
                actor(VICTIM, "PLAYER", 0, 179), actor(ZOMBIE, "ZOMBIE", 2, 0))),
            new ReplayFrame(12, 8, WORLD, VICTIM, List.of(
                actor(VICTIM, "PLAYER", 10, -179), actor(ZOMBIE, "ZOMBIE", 3, 10),
                actor(ARROW, "ARROW", 4, 0))),
            new ReplayFrame(14, 8, WORLD, VICTIM, List.of(
                actor(VICTIM, "PLAYER", 20, -160), actor(ARROW, "ARROW", 9, 0)))
        );
        return new FrozenDeathReplay(
            new ReplayClip(8, VICTIM, WORLD, frames),
            new ReplayDeathMoment(8, VICTIM, WORLD, 14, 12_345L,
                25, 65, 4, 22, 10, "Victim died", "ENTITY_ATTACK"),
            List.of()
        );
    }

    @Test
    void startsWithCapturedActorsAndHoldsSparseHistory() {
        ReplayActorScene scene = new ReplayActorScene(capture(), 6);
        assertEquals(2, scene.at(0).size());
        assertEquals(0.0, scene.at(2).get(VICTIM).x(), 1e-6);
    }

    @Test
    void interpolatesPositionAndAnglesAcrossShortestArc() {
        ReplayActorScene scene = new ReplayActorScene(capture(), 6);
        ReplayActorPose midway = scene.at(3).get(VICTIM);
        assertEquals(5.0, midway.x(), 1e-6);
        assertEquals(180.0, midway.yaw(), 1e-6);
    }

    @Test
    void addsNewActorsOnlyAfterTheirActualKeyframe() {
        ReplayActorScene scene = new ReplayActorScene(capture(), 6);
        assertFalse(scene.at(3).containsKey(ARROW));
        assertTrue(scene.at(4).containsKey(ARROW));
    }

    @Test
    void removesActorsAtNextRecordedKeyframe() {
        ReplayActorScene scene = new ReplayActorScene(capture(), 6);
        assertTrue(scene.at(5).containsKey(ZOMBIE));
        assertFalse(scene.at(6).containsKey(ZOMBIE));
    }

    @Test
    void endsExactlyAtFrozenDeathLocation() {
        ReplayActorScene scene = new ReplayActorScene(capture(), 6);
        ReplayActorPose victim = scene.at(6).get(VICTIM);
        assertEquals(25.0, victim.x(), 1e-6);
        assertEquals(65.0, victim.y(), 1e-6);
        assertEquals(4.0, victim.z(), 1e-6);
        assertEquals(0.0, victim.health(), 1e-6);
        assertEquals(WORLD, scene.worldId());
    }

    @Test
    void scenesAreImmutableAndOutOfRangeIsRejected() {
        ReplayActorScene scene = new ReplayActorScene(capture(), 6);
        assertThrows(UnsupportedOperationException.class,
            () -> scene.at(0).clear());
        assertThrows(IllegalArgumentException.class,
            () -> scene.at(7));
        assertThrows(IllegalArgumentException.class,
            () -> scene.at(-1));
    }

    @Test
    void sessionEmitsDeterministicChanges() {
        ReplayActorSession session = new ReplayActorSession(new ReplayActorScene(capture(), 6));
        List<ReplayActorChange> first = session.advanceTo(0);
        assertEquals(2, first.size());
        assertTrue(first.stream().allMatch(a -> a.kind() == ReplayActorChange.Kind.SPAWN));
        assertTrue(session.advanceTo(1).isEmpty());
        assertEquals(1, session.advanceTo(3).stream()
            .filter(a -> a.kind() == ReplayActorChange.Kind.UPDATE && a.actorId().equals(VICTIM)).count());
        assertEquals(1, session.advanceTo(4).stream()
            .filter(a -> a.kind() == ReplayActorChange.Kind.SPAWN && a.actorId().equals(ARROW)).count());
        assertEquals(1, session.advanceTo(6).stream()
            .filter(a -> a.kind() == ReplayActorChange.Kind.REMOVE && a.actorId().equals(ZOMBIE)).count());
        assertEquals(2, session.clearActors().size());
        session.close();
        assertThrows(IllegalStateException.class, () -> session.advanceTo(6));
    }

    @Test
    void sessionRequiresMonotonicClock() {
        ReplayActorSession session = new ReplayActorSession(new ReplayActorScene(capture(), 6));
        session.advanceTo(5);
        assertThrows(IllegalArgumentException.class, () -> session.advanceTo(4));
    }

    @Test
    void playbackAlwaysCleansUpActors() {
        List<ReplayActorChange> sent = new ArrayList<>();
        class FakeTransport implements ReplayActorTransport {
            boolean closed;
            public void apply(List<ReplayActorChange> changes) { sent.addAll(changes); }
            public void close() { closed = true; }
        }
        FakeTransport transport = new FakeTransport();
        ReplayActorPlayback playback = new ReplayActorPlayback(new ReplayActorScene(capture(), 6), transport);
        playback.renderTick(0);
        playback.close();
        playback.close();
        assertEquals(2, sent.stream().filter(a -> a.kind() == ReplayActorChange.Kind.REMOVE).count());
        assertTrue(transport.closed);
        assertThrows(IllegalStateException.class, () -> playback.renderTick(1));
    }
}
