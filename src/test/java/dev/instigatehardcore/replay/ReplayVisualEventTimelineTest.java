package dev.instigatehardcore.replay;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class ReplayVisualEventTimelineTest {
    private final UUID world = UUID.randomUUID();
    private final UUID victim = UUID.randomUUID();
    private final UUID rival = UUID.randomUUID();

    private ReplayVisualEvent visual(long tick, UUID actor, ReplayVisualEvent.Kind kind) {
        return new ReplayVisualEvent(tick, 4, world, actor, kind);
    }

    private ReplayActorSnapshot actor(UUID id) {
        return new ReplayActorSnapshot(id, "PLAYER", "Test", 10, 63, 10,
            0, 0, false, false, false, 20, ReplayEquipment.empty());
    }

    private FrozenDeathReplay clip(List<ReplayVisualEvent> visualEvents) {
        var frames = List.of(
            new ReplayFrame(100, 4, world, victim, List.of(actor(victim), actor(rival))),
            new ReplayFrame(102, 4, world, victim, List.of(actor(victim), actor(rival))),
            new ReplayFrame(104, 4, world, victim, List.of(actor(victim), actor(rival)))
        );
        var replay = new ReplayClip(4, victim, world, frames);
        var death = new ReplayDeathMoment(4, victim, world, 104, 2000,
            10, 63, 10, 0, 0, "Test slain", "ENTITY_ATTACK");
        return new FrozenDeathReplay(replay, death, List.of(), visualEvents);
    }

    @Test
    void capturesMissedSwingAndCriticalHitForDifferentActors() {
        var events = List.of(
            visual(102, rival, ReplayVisualEvent.Kind.SWING_MAIN),
            visual(102, victim, ReplayVisualEvent.Kind.HURT),
            visual(102, victim, ReplayVisualEvent.Kind.CRITICAL)
        );
        var scene = new ReplayActorScene(clip(events), 140);
        var timeline = new ReplayVisualEventTimeline(scene, events);
        int tick = Math.min(scene.playbackTickOf(102), 135);
        assertEquals(events, timeline.at(tick));
    }

    @Test
    void keepsEventsWithSameTickInCaptureOrder() {
        var events = List.of(
            visual(100, rival, ReplayVisualEvent.Kind.SWING_MAIN),
            visual(100, victim, ReplayVisualEvent.Kind.SWING_OFF)
        );
        var scene = new ReplayActorScene(clip(events));
        var timeline = new ReplayVisualEventTimeline(scene, events);

        // Events in the final five ticks move earlier so the client can
        // render them before the replay ghosts are removed.
        int renderTick = Math.min(
            scene.playbackTickOf(100),
            scene.durationTicks() - 5
        );

        assertEquals(events, timeline.at(renderTick));
    }

    @Test
    void rejectsVisualEventsOutsideClipWindow() {
        assertThrows(IllegalArgumentException.class,
            () -> clip(List.of(visual(99, rival, ReplayVisualEvent.Kind.SWING_MAIN))));
        assertThrows(IllegalArgumentException.class,
            () -> clip(List.of(visual(105, rival, ReplayVisualEvent.Kind.HURT))));
    }

    @Test
    void visualEventListsAreImmutable() {
        List<ReplayVisualEvent> mutable = new ArrayList<>();
        mutable.add(visual(102, rival, ReplayVisualEvent.Kind.SWING_MAIN));
        var frozen = clip(mutable);
        mutable.clear();
        assertEquals(1, frozen.visualEvents().size());
        assertThrows(UnsupportedOperationException.class, () -> frozen.visualEvents().clear());
    }

    @Test
    void bufferBoundsAndWorldTransitions() {
        var buffer = new RollingReplayVisualEventBuffer(3, 100);
        buffer.append(visual(100, rival, ReplayVisualEvent.Kind.SWING_MAIN));
        buffer.append(visual(101, victim, ReplayVisualEvent.Kind.HURT));
        buffer.append(visual(102, rival, ReplayVisualEvent.Kind.SWING_MAIN));
        buffer.append(visual(103, rival, ReplayVisualEvent.Kind.SWING_OFF));
        assertEquals(3, buffer.size());
        assertEquals(101, buffer.snapshot(4, world, 0, 200).getFirst().tick());
        buffer.append(new ReplayVisualEvent(104, 4, UUID.randomUUID(), rival,
            ReplayVisualEvent.Kind.SWING_MAIN));
        assertTrue(buffer.snapshot(4, world, 0, 200).isEmpty());
    }

    @Test
    void mappingsNeverExtendPastPlaybackDuration() {
        var scene = new ReplayActorScene(clip(List.of()), 140);
        assertEquals(-1, scene.playbackTickOf(99));
        assertEquals(140, scene.playbackTickOf(104));
        assertEquals(140, scene.playbackTickOf(1000));
    }

    @Test
    void actorPlaybackEmitsVisualEventsAfterSpawningActors() {
        var event = visual(100, rival, ReplayVisualEvent.Kind.SWING_MAIN);
        var scene = new ReplayActorScene(clip(List.of(event)), 140);
        class MockTransport implements ReplayActorTransport {
            final List<String> operations = new ArrayList<>();
            public void apply(List<ReplayActorChange> changes) {
                if (!changes.isEmpty()) operations.add("actors");
            }
            public void playVisualEvents(List<ReplayVisualEvent> events) {
                if (!events.isEmpty()) operations.add("visual");
            }
            public void close() { }
        }
        var transport = new MockTransport();
        var playback = new ReplayActorPlayback(scene, transport, List.of(event));
        playback.renderTick(0); // Short clip holds the first frame before advancing.
        playback.renderTick(Math.min(scene.playbackTickOf(100), 135));
        assertEquals(List.of("actors", "visual"), transport.operations);
        playback.close();
    }
}
