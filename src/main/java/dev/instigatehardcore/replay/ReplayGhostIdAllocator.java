package dev.instigatehardcore.replay;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A process-wide range for packet-only entities. No Bukkit entity is created.
 * IDs are unique among concurrent replay viewers for the life of the process.
 */
public final class ReplayGhostIdAllocator {
    private static final int START_ID = 1_200_000_000;
    private static final AtomicInteger NEXT = new AtomicInteger(START_ID);

    private ReplayGhostIdAllocator() { }

    public static int nextId() {
        int id = NEXT.getAndIncrement();
        if (id < START_ID) {
            throw new IllegalStateException("Replay ghost ID range exhausted; restart server.");
        }
        return id;
    }
}
