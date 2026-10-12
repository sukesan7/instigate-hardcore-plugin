package dev.instigatehardcore.replay;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * A bounded, main-thread-only buffer of environmental block positions whose
 * final state is not known when Bukkit fires an event. No Bukkit dependency;
 * the caller supplies each block's actual state on subsequent server ticks.
 *
 * Multiple event hooks watching the same position are coalesced: the first
 * observed state is kept until actual changes have been read. Reads are
 * round-robin to avoid repeatedly polling only the oldest positions.
 */
public final class ReplayEnvironmentWatchQueue {
    public record Key(UUID worldId, ReplayBlockPosition position) {
        public Key {
            Objects.requireNonNull(worldId);
            Objects.requireNonNull(position);
        }
    }

    public record Change(
        int attempt, long tick, Key key, String beforeData, String afterData
    ) {
        public Change {
            Objects.requireNonNull(key);
            Objects.requireNonNull(beforeData);
            Objects.requireNonNull(afterData);
        }
    }

    private static final int WATCH_TICKS = 3;

    private static final class Watch {
        private final Key key;
        private final int attempt;
        private final long firstCaptureTick;
        private String lastState;
        private long lastEventTick;

        private Watch(Key key, int attempt, long captureTick,
                      long eventTick, String initialState) {
            this.key = key;
            this.attempt = attempt;
            this.firstCaptureTick = captureTick;
            this.lastEventTick = eventTick;
            this.lastState = initialState;
        }
    }

    private final int maxWatchedBlocks;
    private final int maxReadsPerTick;
    private final LinkedHashMap<Key, Watch> watches = new LinkedHashMap<>();

    public ReplayEnvironmentWatchQueue(int maxWatchedBlocks, int maxReadsPerTick) {
        if (maxWatchedBlocks < 1 || maxReadsPerTick < 1) {
            throw new IllegalArgumentException("Watch and read limits must be positive");
        }
        this.maxWatchedBlocks = Math.min(2048, maxWatchedBlocks);
        this.maxReadsPerTick = Math.min(512, maxReadsPerTick);
    }

    /** No world reads occur here: capture a true PRE-event state. */
    public boolean watch(Key key, String before, int attempt,
                         long captureTick, long serverTick) {
        Objects.requireNonNull(key);
        if (before == null || before.isBlank() || attempt < 1 || captureTick <= 0) {
            return false;
        }
        Watch existing = watches.get(key);
        if (existing != null) {
            if (existing.attempt == attempt) {
                existing.lastEventTick = Math.max(existing.lastEventTick, serverTick);
                return true;
            }
            watches.remove(key);
        }
        if (watches.size() >= maxWatchedBlocks) return false;
        watches.put(key, new Watch(key, attempt, captureTick, serverTick, before));
        return true;
    }

    /**
     * Observe at most maxReadsPerTick positions after their event tick.
     * If a piston or fluid changes again on the next tick, emit that second
     * transition too. Null states indicate missing/unloaded chunks.
     */
    public List<Change> sample(long serverTick, long latestCaptureTick,
                               Function<Key, String> readState) {
        Objects.requireNonNull(readState);
        List<Change> changes = new ArrayList<>();
        List<Watch> deferred = new ArrayList<>();
        int reads = 0;
        Iterator<Map.Entry<Key, Watch>> iterator = watches.entrySet().iterator();
        while (iterator.hasNext()) {
            Watch watch = iterator.next().getValue();
            if (serverTick > watch.lastEventTick + WATCH_TICKS) {
                iterator.remove();
                continue;
            }
            if (serverTick <= watch.lastEventTick || reads >= maxReadsPerTick) {
                continue;
            }
            reads++;
            String actual = readState.apply(watch.key);
            if (actual != null && !actual.isBlank() && !actual.equals(watch.lastState)) {
                changes.add(new Change(watch.attempt,
                    Math.max(watch.firstCaptureTick, latestCaptureTick),
                    watch.key, watch.lastState, actual));
                watch.lastState = actual;
            }
            iterator.remove();
            // Round robin: defer sampled entries behind those awaiting a read.
            if (serverTick < watch.lastEventTick + WATCH_TICKS) {
                deferred.add(watch);
            }
        }
        for (Watch watch : deferred) watches.put(watch.key, watch);
        return List.copyOf(changes);
    }

    /** Prevent a delayed fallback from repeating an exact event transition. */
    public void discard(Key key) {
        watches.remove(Objects.requireNonNull(key));
    }

    public boolean canAccept(Key key) {
        return watches.containsKey(Objects.requireNonNull(key))
            || watches.size() < maxWatchedBlocks;
    }

    public int size() { return watches.size(); }

    public void clear() { watches.clear(); }
}
