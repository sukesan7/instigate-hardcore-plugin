package dev.instigatehardcore.replay;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReplayEnvironmentWatchQueueTest {
    private static final UUID WORLD = UUID.randomUUID();
    private static final ReplayEnvironmentWatchQueue.Key A =
        new ReplayEnvironmentWatchQueue.Key(WORLD, new ReplayBlockPosition(0, 64, 0));
    private static final ReplayEnvironmentWatchQueue.Key B =
        new ReplayEnvironmentWatchQueue.Key(WORLD, new ReplayBlockPosition(1, 64, 0));

    @Test
    void capturesNextTickAndPreservesBeforeState() {
        var queue = new ReplayEnvironmentWatchQueue(16, 16);
        assertTrue(queue.watch(A, "minecraft:air", 3, 100, 200));
        assertTrue(queue.sample(200, 100, key -> "minecraft:water[level=0]").isEmpty());
        var changes = queue.sample(201, 102, key -> "minecraft:water[level=0]");
        assertEquals(1, changes.size());
        assertEquals("minecraft:air", changes.getFirst().beforeData());
        assertEquals("minecraft:water[level=0]", changes.getFirst().afterData());
        assertEquals(102L, changes.getFirst().tick());
        assertTrue(queue.sample(202, 104, key -> "minecraft:water[level=0]").isEmpty());
    }

    @Test
    void successiveFluidOrPistonChangesFormAnOrderedHistory() {
        var queue = new ReplayEnvironmentWatchQueue(16, 16);
        queue.watch(A, "minecraft:air", 3, 100, 200);
        var first = queue.sample(201, 102, key -> "minecraft:water[level=1]");
        var second = queue.sample(202, 104, key -> "minecraft:water[level=2]");
        assertEquals("minecraft:air", first.getFirst().beforeData());
        assertEquals("minecraft:water[level=1]", second.getFirst().beforeData());
        assertEquals(104L, second.getFirst().tick());
    }

    @Test
    void duplicateWatchKeepsFirstPreEventState() {
        var queue = new ReplayEnvironmentWatchQueue(16, 16);
        queue.watch(A, "minecraft:air", 3, 100, 200);
        queue.watch(A, "minecraft:water[level=1]", 3, 100, 200);
        assertEquals(1, queue.size());
        var change = queue.sample(201, 102, key -> "minecraft:water[level=0]");
        assertEquals("minecraft:air", change.getFirst().beforeData());
    }

    @Test
    void capsWatchedPositionsAndRoundRobinsReads() {
        var queue = new ReplayEnvironmentWatchQueue(2, 1);
        queue.watch(A, "minecraft:air", 3, 100, 200);
        queue.watch(B, "minecraft:air", 3, 100, 200);
        assertFalse(queue.watch(new ReplayEnvironmentWatchQueue.Key(WORLD,
            new ReplayBlockPosition(2, 64, 0)), "minecraft:air", 3, 100, 200));
        var first = queue.sample(201, 102, key -> "minecraft:stone");
        var second = queue.sample(202, 104, key -> "minecraft:dirt");
        assertEquals(A, first.getFirst().key());
        assertEquals(B, second.getFirst().key());
    }

    @Test
    void expiresAndClearsWithoutChangingWorld() {
        var queue = new ReplayEnvironmentWatchQueue(16, 16);
        queue.watch(A, "minecraft:air", 3, 100, 200);
        assertTrue(queue.sample(205, 105, key -> "minecraft:stone").isEmpty());
        assertEquals(0, queue.size());
        queue.watch(B, "minecraft:air", 3, 108, 206);
        queue.clear();
        assertEquals(0, queue.size());
    }

    @Test
    void exactEventRemovesStaleFluidWatchWithoutASecondTransition() {
        var queue = new ReplayEnvironmentWatchQueue(16, 16);
        queue.watch(A, "minecraft:water[level=0]", 3, 100, 200);
        queue.discard(A);
        assertTrue(queue.sample(201, 102, key -> "minecraft:air").isEmpty());
        assertEquals(0, queue.size());
    }

    @Test
    void missingChunkSkipsChangeAndCanRecoverNextTick() {
        var queue = new ReplayEnvironmentWatchQueue(16, 16);
        queue.watch(A, "minecraft:air", 3, 100, 200);
        assertTrue(queue.sample(201, 102, key -> null).isEmpty());
        assertEquals(1, queue.sample(202, 104, key -> "minecraft:stone").size());
    }
}
