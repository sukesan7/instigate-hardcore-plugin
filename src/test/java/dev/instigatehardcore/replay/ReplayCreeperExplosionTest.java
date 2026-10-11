package dev.instigatehardcore.replay;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class ReplayCreeperExplosionTest {
    private final UUID victim = UUID.randomUUID();
    private final UUID creeper = UUID.randomUUID();
    private final UUID world = UUID.randomUUID();

    private ReplayCreeperExplosionEvent blast(long tick) {
        return new ReplayCreeperExplosionEvent(tick, 3, world, creeper, 8.5, 65, 8, false);
    }

    private ReplayActorSnapshot creeperActor(ReplayCreeperState fuse) {
        return new ReplayActorSnapshot(creeper, "CREEPER", "Creeper", 8, 64, 8,
            0, 0, false, false, false, 20, ReplayEquipment.empty(),
            false, false, "AIR", fuse);
    }

    private FrozenDeathReplay frozen(List<ReplayCreeperExplosionEvent> explosions) {
        var frames = List.of(
            new ReplayFrame(100, 3, world, victim, List.of(creeperActor(ReplayCreeperState.NONE))),
            new ReplayFrame(102, 3, world, victim, List.of(creeperActor(
                new ReplayCreeperState(12, 30, false, true)))) ,
            new ReplayFrame(104, 3, world, victim, List.of(creeperActor(
                new ReplayCreeperState(24, 30, false, true))))
        );
        var clip = new ReplayClip(3, victim, world, frames);
        var death = new ReplayDeathMoment(3, victim, world, 104, 10000,
            8, 65, 8, 0, 0, "Explosion", "ENTITY_EXPLOSION");
        return new FrozenDeathReplay(clip, death, List.of(), List.of(), explosions);
    }

    @Test
    void creeperStatesKeepChargedAppearanceAndFuse() {
        var state = new ReplayCreeperState(12, 30, false, true);
        assertTrue(state.swelling());
        assertTrue(state.powered());
        assertFalse(state.ignited());
        assertTrue(state.sameMetadata(new ReplayCreeperState(19, 30, false, true)));
        assertFalse(state.sameMetadata(ReplayCreeperState.NONE));
        assertFalse(new ReplayCreeperState(0, 30, false, false).swelling());
        assertTrue(new ReplayCreeperState(0, 30, true, false).swelling());
        assertThrows(IllegalArgumentException.class,
            () -> new ReplayCreeperState(-1, 30, false, false));
    }

    @Test
    void actorSnapshotCarriesFuseThroughInterpolation() {
        var start = creeperActor(ReplayCreeperState.NONE);
        var end = creeperActor(new ReplayCreeperState(8, 30, false, true));
        assertEquals(ReplayCreeperState.NONE,
            ReplayActorPose.interpolate(start, end, 0.5).creeperState());
        assertEquals(end.creeperState(),
            ReplayActorPose.interpolate(start, end, 1.0).creeperState());
    }

    @Test
    void oldActorConstructorsDefaultToIdleCreeper() {
        var old = new ReplayActorSnapshot(creeper, "CREEPER", "C", 1, 2, 3,
            0, 0, false, false, false, 20, ReplayEquipment.empty());
        assertEquals(ReplayCreeperState.NONE, ReplayActorPose.from(old).creeperState());
    }

    @Test
    void explosionDoesNotDependOnCreeperRemainingInLastFrame() {
        var explosion = blast(102);
        var scene = new ReplayActorScene(frozen(List.of(explosion)));
        var timeline = new ReplayCreeperExplosionTimeline(scene, List.of(explosion));
        int tick = Math.min(scene.playbackTickOf(102), scene.durationTicks() - 5);
        assertEquals(List.of(explosion), timeline.at(tick));
    }

    @Test
    void terminalExplosionRendersBeforeCleanup() {
        var explosion = blast(104);
        var scene = new ReplayActorScene(frozen(List.of(explosion)));
        var timeline = new ReplayCreeperExplosionTimeline(scene, List.of(explosion));
        assertEquals(List.of(explosion), timeline.at(135));
        assertTrue(timeline.at(140).isEmpty());
    }

    @Test
    void wrongWorldAndAttemptAreIgnoredByTimeline() {
        var scene = new ReplayActorScene(frozen(List.of()));
        var alien = new ReplayCreeperExplosionEvent(102, 3, UUID.randomUUID(),
            creeper, 8, 65, 8, false);
        var future = new ReplayCreeperExplosionEvent(102, 4, world, creeper, 8, 65, 8, false);
        var timeline = new ReplayCreeperExplosionTimeline(scene, List.of(alien, future));
        for (int tick = 0; tick <= 140; tick++) assertTrue(timeline.at(tick).isEmpty());
    }

    @Test
    void frozenReplayRejectsExplosionsOutsideClip() {
        assertThrows(IllegalArgumentException.class, () -> frozen(List.of(blast(99))));
        assertThrows(IllegalArgumentException.class, () -> frozen(List.of(blast(105))));
    }

    @Test
    void frozenExplosionListIsImmutable() {
        var input = new ArrayList<>(List.of(blast(102)));
        var replay = frozen(input);
        input.clear();
        assertEquals(1, replay.creeperExplosions().size());
        assertThrows(UnsupportedOperationException.class,
            () -> replay.creeperExplosions().clear());
    }

    @Test
    void explosionBufferIsBoundedAndFiltersByWindow() {
        var buffer = new RollingReplayCreeperExplosionBuffer(2, 4);
        buffer.append(blast(100));
        buffer.append(blast(102));
        buffer.append(blast(106));
        assertEquals(List.of(blast(102), blast(106)),
            buffer.snapshot(3, world, 0, 200));
        assertTrue(buffer.snapshot(3, UUID.randomUUID(), 0, 200).isEmpty());
        assertTrue(buffer.snapshot(4, world, 0, 200).isEmpty());
        assertEquals(List.of(blast(106)), buffer.snapshot(3, world, 104, 108));
        assertThrows(IllegalArgumentException.class, () -> buffer.append(blast(105)));
    }

    @Test
    void explosionEventsReachTransportOncePerTimelineTick() {
        var explosion = blast(102);
        var scene = new ReplayActorScene(frozen(List.of(explosion)));
        class FakeTransport implements ReplayActorTransport {
            final List<ReplayCreeperExplosionEvent> emitted = new ArrayList<>();
            public void apply(List<ReplayActorChange> changes) { }
            public void playCreeperExplosions(List<ReplayCreeperExplosionEvent> events) {
                emitted.addAll(events);
            }
            public void close() { }
        }
        var transport = new FakeTransport();
        var playback = new ReplayActorPlayback(scene, transport, List.of(),
            List.of(explosion), false, 24);
        for (int tick = 0; tick <= 140; tick++) playback.renderTick(tick);
        assertEquals(List.of(explosion), transport.emitted);
        playback.close();
    }
}
