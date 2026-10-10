package dev.instigatehardcore.replay;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class ReplayGhostIdAllocatorTest {
    @Test
    void providesDistinctNonNegativePacketEntityIds() {
        Set<Integer> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            int id = ReplayGhostIdAllocator.nextId();
            assertTrue(id >= 1_200_000_000);
            assertTrue(ids.add(id));
        }
    }
}
