package dev.instigatehardcore.replay;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RollingReplayBufferTest {

    private final UUID subject = UUID.randomUUID();
    private final UUID overworld = UUID.randomUUID();
    private final UUID nether = UUID.randomUUID();

    private ReplayFrame frame(long tick, int attempt, UUID world) {
        return new ReplayFrame(tick, attempt, world, subject, List.of(
            new ReplayActorSnapshot(
                subject, "PLAYER", "Tester", 1, 64, 2, 0, 0,
                false, false, false, 20,
                ReplayEquipment.empty()
            )
        ));
    }

    @Test
    void maintainsBoundedSevenSecondWindow() {
        RollingReplayBuffer buffer = new RollingReplayBuffer(70);
        for (int i = 1; i <= 100; i++) {
            buffer.append(frame(i * 2, 3, overworld));
        }
        assertEquals(70, buffer.size());
        assertEquals(62, buffer.snapshot().getFirst().tick());
        assertEquals(200, buffer.snapshot().getLast().tick());
    }

    @Test
    void dimensionChangeStartsNewScene() {
        RollingReplayBuffer buffer = new RollingReplayBuffer(70);
        buffer.append(frame(2, 3, overworld));
        buffer.append(frame(4, 3, nether));
        assertEquals(1, buffer.size());
        assertEquals(nether, buffer.snapshot().getFirst().worldId());
    }

    @Test
    void attemptChangeDropsOldFrames() {
        RollingReplayBuffer buffer = new RollingReplayBuffer(70);
        buffer.append(frame(2, 3, overworld));
        buffer.append(frame(4, 4, overworld));
        assertEquals(1, buffer.size());
        assertEquals(4, buffer.snapshot().getFirst().attempt());
    }

    @Test
    void nonIncreasingTickResetsTimeline() {
        RollingReplayBuffer buffer = new RollingReplayBuffer(70);
        buffer.append(frame(20, 3, overworld));
        buffer.append(frame(20, 3, overworld));
        assertEquals(1, buffer.size());
    }

    @Test
    void snapshotListCannotBeModified() {
        RollingReplayBuffer buffer = new RollingReplayBuffer(70);
        buffer.append(frame(2, 3, overworld));
        List<ReplayFrame> frozen = buffer.snapshot();
        assertThrows(UnsupportedOperationException.class, () -> frozen.clear());
        buffer.clear();
        assertEquals(1, frozen.size());
    }

    @Test
    void frameCopiesActorList() {
        ArrayList<ReplayActorSnapshot> actors = new ArrayList<>();
        actors.add(frame(2, 3, overworld).actors().getFirst());
        ReplayFrame frame = new ReplayFrame(2, 3, overworld, subject, actors);
        actors.clear();
        assertEquals(1, frame.actors().size());
        assertThrows(UnsupportedOperationException.class, () -> frame.actors().clear());
    }

    @Test
    void clipRejectsMixedDimensions() {
        assertThrows(IllegalArgumentException.class, () -> new ReplayClip(
            3, subject, overworld, List.of(frame(2, 3, nether))
        ));
    }

    @Test
    void clipCopiesFramesAndRejectsEmpty() {
        assertThrows(IllegalArgumentException.class, () -> new ReplayClip(
            3, subject, overworld, List.of()
        ));
        ReplayClip clip = new ReplayClip(
            3, subject, overworld, List.of(frame(2, 3, overworld))
        );
        assertTrue(clip.frames().getFirst().actors().size() == 1);
        assertThrows(UnsupportedOperationException.class, () -> clip.frames().clear());
    }
}
