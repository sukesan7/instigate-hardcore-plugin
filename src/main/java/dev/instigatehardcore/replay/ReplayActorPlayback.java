package dev.instigatehardcore.replay;

import java.util.Objects;

/**
 * One-viewer coordinator: scene -> delta session -> packet transport.
 * Can be driven once per server tick by Phase 9D's shared camera scheduler.
 * On errors the caller can close() to release all already-visible ghosts.
 */
public final class ReplayActorPlayback implements AutoCloseable {
    private final ReplayActorScene scene;
    private final ReplayActorSession session;
    private final ReplayActorTransport transport;
    private final ReplayVisualEventTimeline visualTimeline;
    private final ReplayMovementEffectPlanner movementPlanner;
    private final ReplayCreeperExplosionTimeline explosionTimeline;
    private boolean closed;

    public ReplayActorPlayback(ReplayActorScene scene, ReplayActorTransport transport) {
        this(scene, transport, java.util.List.of());
    }

    public ReplayActorPlayback(
        ReplayActorScene scene,
        ReplayActorTransport transport,
        java.util.List<ReplayVisualEvent> visualEvents
    ) {
        this(scene, transport, visualEvents, true, 24);
    }

    public ReplayActorPlayback(
        ReplayActorScene scene,
        ReplayActorTransport transport,
        java.util.List<ReplayVisualEvent> visualEvents,
        boolean movementParticlesEnabled,
        int maxParticlesPerTick
    ) {
        this(scene, transport, visualEvents, java.util.List.of(),
            movementParticlesEnabled, maxParticlesPerTick);
    }

    public ReplayActorPlayback(
        ReplayActorScene scene,
        ReplayActorTransport transport,
        java.util.List<ReplayVisualEvent> visualEvents,
        java.util.List<ReplayCreeperExplosionEvent> explosions,
        boolean movementParticlesEnabled,
        int maxParticlesPerTick
    ) {
        this.scene = Objects.requireNonNull(scene);
        this.session = new ReplayActorSession(scene);
        this.transport = Objects.requireNonNull(transport);
        this.visualTimeline = new ReplayVisualEventTimeline(scene, visualEvents);
        this.explosionTimeline = new ReplayCreeperExplosionTimeline(scene, explosions);
        this.movementPlanner = movementParticlesEnabled
            ? new ReplayMovementEffectPlanner(Math.max(1, Math.min(64, maxParticlesPerTick)))
            : null;
    }

    public void renderTick(int playbackTick) {
        if (closed) {
            throw new IllegalStateException("Playback closed.");
        }
        transport.apply(session.advanceTo(playbackTick));
        transport.playVisualEvents(visualTimeline.at(playbackTick));
        transport.playCreeperExplosions(explosionTimeline.at(playbackTick));
        if (movementPlanner != null) {
            transport.playMovementEffects(movementPlanner.advance(
                playbackTick, scene.at(playbackTick)));
        }
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
