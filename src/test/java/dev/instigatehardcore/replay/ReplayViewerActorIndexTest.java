package dev.instigatehardcore.replay;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReplayViewerActorIndexTest {
    private static ReplayActorSnapshot actor(UUID id) {
        return new ReplayActorSnapshot(id, "PLAYER", "Test", 0, 64, 0, 0, 0,
            false, false, false, 20, ReplayEquipment.empty());
    }

    @Test
    void includesAllDistinctActorsEvenIfNotInFinalFrame() {
        UUID subject = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        UUID mob = UUID.randomUUID();
        ReplayClip clip = new ReplayClip(3, subject, world, List.of(
            new ReplayFrame(100, 3, world, subject, List.of(actor(subject), actor(mob))),
            new ReplayFrame(102, 3, world, subject, List.of(actor(subject)))
        ));
        assertEquals(List.of(subject, mob), List.copyOf(ReplayViewerActorIndex.fromClip(clip)));
    }

    @Test
    void outputCannotBeMutated() {
        UUID subject = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        ReplayClip clip = new ReplayClip(1, subject, world, List.of(
            new ReplayFrame(1, 1, world, subject, List.of(actor(subject)))
        ));
        Set<UUID> ids = ReplayViewerActorIndex.fromClip(clip);
        assertThrows(UnsupportedOperationException.class, () -> ids.add(UUID.randomUUID()));
    }

    @Test
    void emptyActorFramesAreSupported() {
        UUID subject = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        ReplayClip clip = new ReplayClip(1, subject, world, List.of(
            new ReplayFrame(1, 1, world, subject, List.of())
        ));
        assertTrue(ReplayViewerActorIndex.fromClip(clip).isEmpty());
    }
}
