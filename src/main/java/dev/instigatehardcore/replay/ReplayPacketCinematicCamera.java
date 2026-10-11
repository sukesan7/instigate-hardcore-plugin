package dev.instigatehardcore.replay;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.vector.vecdelta.LinearVecDelta;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCamera;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A per-viewer, packet-only cinematic camera target.
 *
 * Living entities use Minecraft's built-in client movement interpolation;
 * the old non-living MARKER entity does not. This invisible, marker-flagged
 * armor stand has no rendered model and does not exist on the server.
 *
 * Important: packet deltas are quantized, so the next relative move must be
 * calculated from the last *encoded* client position, not the last target.
 * This avoids accumulating sub-block drift over a long replay.
 */
public final class ReplayPacketCinematicCamera implements AutoCloseable {
    private static final int ARMOR_STAND_FLAGS_INDEX = 15; // 26.3 ArmorStand flags
    private static final byte INVISIBLE = 0x20; // base entity flags, index 0
    private static final byte ARMOR_STAND_MARKER = 0x10;

    private final Player viewer;
    private final UUID worldId;
    private final int cameraEntityId;
    private ReplayCinematicCameraPath.Frame lastSent;
    private boolean closed;
    private boolean cameraAttached;

    public ReplayPacketCinematicCamera(
        Player viewer,
        UUID worldId,
        ReplayCinematicCameraPath.Frame initial
    ) {
        this.viewer = Objects.requireNonNull(viewer);
        this.worldId = Objects.requireNonNull(worldId);
        this.lastSent = Objects.requireNonNull(initial);
        assertMainThread();
        verifyViewer();
        this.cameraEntityId = ReplayGhostIdAllocator.nextId();

        try {
            send(new WrapperPlayServerSpawnEntity(
                cameraEntityId, UUID.randomUUID(), EntityTypes.ARMOR_STAND,
                new Location(initial.x(), initial.y(), initial.z(),
                    initial.yaw(), initial.pitch()),
                initial.yaw(), 0, new Vector3d(0, 0, 0)
            ));
            // Invisible on the base entity, and marker-size/no hitbox on the
            // armor stand. Do this BEFORE attaching the viewer's camera.
            send(new WrapperPlayServerEntityMetadata(cameraEntityId, List.of(
                new EntityData<>(0, EntityDataTypes.BYTE, INVISIBLE),
                new EntityData<>(ARMOR_STAND_FLAGS_INDEX, EntityDataTypes.BYTE,
                    ARMOR_STAND_MARKER)
            )));
            // Armor stands are living entities. Their head yaw is stored
            // separately from the movement/body yaw. The spectator camera
            // follows the head/view orientation, so it must be initialized
            // before switching the viewer to this entity.
            send(new WrapperPlayServerEntityHeadLook(cameraEntityId, initial.yaw()));
            send(new WrapperPlayServerCamera(cameraEntityId));
            cameraAttached = true;
        } catch (RuntimeException error) {
            try { send(new WrapperPlayServerCamera(viewer.getEntityId())); }
            catch (RuntimeException ignored) { }
            try { send(new WrapperPlayServerDestroyEntities(cameraEntityId)); }
            catch (RuntimeException ignored) { }
            throw error;
        }
    }

    public void moveTo(ReplayCinematicCameraPath.Frame frame) {
        assertMainThread();
        if (closed) return;
        verifyViewer();
        Objects.requireNonNull(frame);
        ReplayCinematicCameraPath.Frame from = lastSent;
        ReplayEntityMotion.Plan plan = ReplayEntityMotion.plan(
            from.x(), from.y(), from.z(), frame.x(), frame.y(), frame.z(),
            from.yaw(), from.pitch(), frame.yaw(), frame.pitch()
        );
        switch (plan.kind()) {
            case NONE -> { return; }
            case RELATIVE -> {
                LinearVecDelta delta = new LinearVecDelta(plan.dx(), plan.dy(), plan.dz());
                send(new WrapperPlayServerEntityRelativeMoveAndRotation(
                    cameraEntityId, delta, frame.yaw(), frame.pitch(), false
                ));
                syncHeadYaw(from.yaw(), frame.yaw());
                lastSent = new ReplayCinematicCameraPath.Frame(
                    from.x() + delta.dx(), from.y() + delta.dy(),
                    from.z() + delta.dz(), frame.yaw(), frame.pitch()
                );
            }
            case TELEPORT -> {
                send(new WrapperPlayServerEntityTeleport(
                    cameraEntityId,
                    new Vector3d(frame.x(), frame.y(), frame.z()),
                    frame.yaw(), frame.pitch(), false
                ));
                syncHeadYaw(from.yaw(), frame.yaw());
                lastSent = frame;
            }
        }
    }

    /**
     * Entity position/rotation packets do not update a living entity's
     * separate head yaw. Spectator cameras depend on that head orientation.
     * Send head-look packets only when the yaw changes, including when the
     * camera turns in place (no position delta).
     */
    private void syncHeadYaw(float previousYaw, float targetYaw) {
        if (ReplayCameraHeadRotation.changed(previousYaw, targetYaw)) {
            send(new WrapperPlayServerEntityHeadLook(cameraEntityId, targetYaw));
        }
    }

    private void verifyViewer() {
        if (!viewer.isOnline() || viewer.getGameMode() != GameMode.SPECTATOR
            || !viewer.getWorld().getUID().equals(worldId)) {
            throw new IllegalStateException(
                "Cinematic camera viewer left the replay world or spectator mode."
            );
        }
    }

    private void send(PacketWrapper<?> packet) {
        if (viewer.isOnline()) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
        }
    }

    @Override
    public void close() {
        assertMainThread();
        if (closed) return;
        closed = true;
        if (!viewer.isOnline()) return;
        // Release camera before destroying its target, including on errors.
        if (cameraAttached) {
            try { send(new WrapperPlayServerCamera(viewer.getEntityId())); }
            catch (RuntimeException ignored) { }
        }
        try { send(new WrapperPlayServerDestroyEntities(cameraEntityId)); }
        catch (RuntimeException ignored) { }
        cameraAttached = false;
    }

    private static void assertMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Cinematic camera packets require the Paper main thread.");
        }
    }
}
