package dev.instigatehardcore.replay;

import java.util.Objects;

/**
 * One-viewer coordinator: scene -> delta session -> packet transport.
 * Can be driven once per server tick by Phase 9D's shared camera scheduler.
 * On errors the caller can close() to release all already-visible ghosts.
 */
public final class ReplayActorPlayback implements AutoCloseable {
    private final ReplayActorSession session;
    private final ReplayActorTransport transport;
    private boolean closed;

    public ReplayActorPlayback(ReplayActorScene scene, ReplayActorTransport transport) {
        this.session = new ReplayActorSession(Objects.requireNonNull(scene));
        this.transport = Objects.requireNonNull(transport);
    }

    public void renderTick(int playbackTick) {
        if (closed) {
            throw new IllegalStateException("Playback closed.");
        }
        transport.apply(session.advanceTo(playbackTick));
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            transport.apply(session.clearActors());
        } finally {
            try {
                transport.close();
            } finally {
                session.close();
            }
        }
    }
}
