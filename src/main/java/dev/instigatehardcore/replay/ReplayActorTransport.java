package dev.instigatehardcore.replay;

import java.util.List;

/**
 * Packet-only actor rendering boundary. Future Paper/PacketEvents adapter sends
 * these instructions to ONE viewer. Never mutate Bukkit's live entities here.
 *
 * The same caller must send session.clearActors() through apply() before
 * discarding its renderer or transferring the viewer to another dimension.
 */
public interface ReplayActorTransport extends AutoCloseable {
    void apply(List<ReplayActorChange> changes);

    @Override
    void close();
}
