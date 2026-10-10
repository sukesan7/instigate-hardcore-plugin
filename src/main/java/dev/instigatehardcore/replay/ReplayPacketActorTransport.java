package dev.instigatehardcore.replay;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCamera;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * PacketEvents 2.14.0 implementation of Phase 9C's per-viewer transport.
 *
 * No real entities are spawned, and no server world is modified. Each viewer
 * has independent client entity IDs, profiles, equipment and camera state.
 * Caller must drive on the Paper primary server thread and close on every
 * success, error, logout, dimension change and plugin shutdown.
 *
 * IMPORTANT: This intentionally renders only the recorded actor snapshots.
 * It does not rewind blocks, sound, particle events or combat animations.
 */
public final class ReplayPacketActorTransport implements ReplayActorTransport {
    private record Ghost(int entityId, UUID profileId, boolean player, ReplayActorPose lastPose) { }

    private final Player viewer;
    private final UUID replayWorldId;
    private final Map<UUID, Ghost> ghosts = new LinkedHashMap<>();
    private int cameraTargetId = -1;
    private boolean closed;

    public ReplayPacketActorTransport(Player viewer, UUID replayWorldId) {
        this.viewer = Objects.requireNonNull(viewer);
        this.replayWorldId = Objects.requireNonNull(replayWorldId);
        assertMainThread();
        if (!viewer.isOnline() || !viewer.getWorld().getUID().equals(replayWorldId)) {
            throw new IllegalArgumentException("Viewer must be online in the clip dimension.");
        }
    }

    @Override
    public void apply(List<ReplayActorChange> changes) {
        assertMainThread();
        if (closed) {
            throw new IllegalStateException("Replay packet transport is closed.");
        }
        Objects.requireNonNull(changes);
        if (!viewer.isOnline() || !viewer.getWorld().getUID().equals(replayWorldId)) {
            close();
            throw new IllegalStateException("Viewer disconnected or left the replay dimension.");
        }
        for (ReplayActorChange change : changes) {
            switch (change.kind()) {
                case SPAWN -> spawn(change.pose());
                case UPDATE -> update(change.pose());
                case REMOVE -> remove(change.actorId());
            }
        }
    }

    /** Experimental victim point-of-view; the normal default remains free spectator. */
    public boolean focusOnActor(UUID actorId) {
        assertMainThread();
        if (closed || !viewer.isOnline() || viewer.getGameMode() != org.bukkit.GameMode.SPECTATOR) {
            return false;
        }
        Ghost ghost = ghosts.get(actorId);
        if (ghost == null) {
            return false;
        }
        send(new WrapperPlayServerCamera(ghost.entityId()));
        cameraTargetId = ghost.entityId();
        return true;
    }

    public void resetCamera() {
        assertMainThread();
        if (cameraTargetId != -1 && viewer.isOnline()) {
            send(new WrapperPlayServerCamera(viewer.getEntityId()));
        }
        cameraTargetId = -1;
    }

    private void spawn(ReplayActorPose pose) {
        if (ghosts.containsKey(pose.id())) {
            update(pose);
            return;
        }

        EntityType entityType;
        try {
            // Recorded names come from Bukkit's EntityType.name(), e.g. ZOMBIE.
            org.bukkit.entity.EntityType bukkitType = org.bukkit.entity.EntityType.valueOf(
                pose.entityType().toUpperCase(Locale.ROOT)
            );
            entityType = SpigotConversionUtil.fromBukkitEntityType(bukkitType);
        } catch (IllegalArgumentException exception) {
            return; // Unknown or removed entity type: omit it rather than break replay.
        }
        if (entityType == null) {
            return;
        }

        boolean isPlayer = entityType == EntityTypes.PLAYER;
        int entityId = ReplayGhostIdAllocator.nextId();
        UUID fakeUuid = UUID.nameUUIDFromBytes(
            ("instigate-replay:" + viewer.getUniqueId() + ":" + pose.id() + ":" + entityId)
                .getBytes(StandardCharsets.UTF_8)
        );

        // Register before sending, so even partial spawns are cleaned up.
        ghosts.put(pose.id(), new Ghost(entityId, fakeUuid, isPlayer, pose));
        if (isPlayer) {
            UserProfile profile = new UserProfile(fakeUuid, trimmedName(pose.name()), textureProperties(pose.id()));
            var playerInfo = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                profile, false, 0, GameMode.SURVIVAL, null, null
            );
            send(new WrapperPlayServerPlayerInfoUpdate(
                EnumSet.of(
                    WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                    WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE,
                    WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED
                ), List.of(playerInfo)
            ));
        }

        send(new WrapperPlayServerSpawnEntity(
            entityId, fakeUuid, entityType,
            new Location(pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch()),
            pose.yaw(), 0, new Vector3d(0, 0, 0)
        ));
        send(new WrapperPlayServerEntityHeadLook(entityId, pose.yaw()));
        sendMetadata(entityId, pose);
        sendEquipment(entityId, pose.equipment());
    }

    private void update(ReplayActorPose pose) {
        Ghost ghost = ghosts.get(pose.id());
        if (ghost == null) {
            // Unsupported types may never have been spawned.
            return;
        }
        if (!ghost.lastPose().entityType().equals(pose.entityType())) {
            remove(pose.id());
            spawn(pose);
            return;
        }
        send(new WrapperPlayServerEntityTeleport(
            ghost.entityId(), new Vector3d(pose.x(), pose.y(), pose.z()),
            pose.yaw(), pose.pitch(), false
        ));
        send(new WrapperPlayServerEntityHeadLook(ghost.entityId(), pose.yaw()));
        if (ghost.lastPose().sneaking() != pose.sneaking()
            || ghost.lastPose().burning() != pose.burning()
            || ghost.lastPose().gliding() != pose.gliding()) {
            sendMetadata(ghost.entityId(), pose);
        }
        if (!ghost.lastPose().equipment().equals(pose.equipment())) {
            sendEquipment(ghost.entityId(), pose.equipment());
        }
        ghosts.put(pose.id(), new Ghost(ghost.entityId(), ghost.profileId(), ghost.player(), pose));
    }

    private void sendMetadata(int id, ReplayActorPose pose) {
        // Shared entity flags: on fire 0x01, crouching 0x02, gliding 0x80.
        int flags = (pose.burning() ? 0x01 : 0)
            | (pose.sneaking() ? 0x02 : 0)
            | (pose.gliding() ? 0x80 : 0);
        send(new WrapperPlayServerEntityMetadata(
            id, List.of(new EntityData<>(0, EntityDataTypes.BYTE, (byte) flags))
        ));
    }

    private void sendEquipment(int id, ReplayEquipment equipment) {
        List<Equipment> slots = new ArrayList<>(6);
        slots.add(new Equipment(EquipmentSlot.MAIN_HAND, item(equipment.mainHand())));
        slots.add(new Equipment(EquipmentSlot.OFF_HAND, item(equipment.offHand())));
        slots.add(new Equipment(EquipmentSlot.HELMET, item(equipment.helmet())));
        slots.add(new Equipment(EquipmentSlot.CHEST_PLATE, item(equipment.chestplate())));
        slots.add(new Equipment(EquipmentSlot.LEGGINGS, item(equipment.leggings())));
        slots.add(new Equipment(EquipmentSlot.BOOTS, item(equipment.boots())));
        send(new WrapperPlayServerEntityEquipment(id, slots));
    }

    private static com.github.retrooper.packetevents.protocol.item.ItemStack item(String materialName) {
        Material material = Material.matchMaterial(materialName);
        if (material == null || material.isAir()) {
            return com.github.retrooper.packetevents.protocol.item.ItemStack.EMPTY;
        }
        return SpigotConversionUtil.fromBukkitItemStack(new ItemStack(material));
    }

    private static String trimmedName(String name) {
        if (name.isBlank()) {
            return "ReplayActor";
        }
        return name.length() <= 16 ? name : name.substring(0, 16);
    }

    private static List<TextureProperty> textureProperties(UUID actualActorId) {
        Player source = Bukkit.getPlayer(actualActorId);
        if (source == null || !source.isOnline()) {
            return List.of();
        }
        List<TextureProperty> result = new ArrayList<>();
        for (ProfileProperty property : source.getPlayerProfile().getProperties()) {
            if ("textures".equals(property.getName())) {
                result.add(new TextureProperty(
                    property.getName(), property.getValue(), property.getSignature()
                ));
            }
        }
        return List.copyOf(result);
    }

    private void remove(UUID actorId) {
        Ghost ghost = ghosts.remove(actorId);
        if (ghost == null) {
            return;
        }
        if (cameraTargetId == ghost.entityId()) {
            // Returning the camera to the viewer first prevents a dangling POV.
            resetCamera();
        }
        send(new WrapperPlayServerDestroyEntities(ghost.entityId()));
        if (ghost.player()) {
            send(new WrapperPlayServerPlayerInfoRemove(ghost.profileId()));
        }
    }

    private void send(PacketWrapper<?> packet) {
        if (viewer.isOnline()) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
        }
    }

    private static void assertMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Replay packet rendering must run on Paper's main thread.");
        }
    }

    @Override
    public void close() {
        assertMainThread();
        if (closed) {
            return;
        }
        closed = true;
        try {
            resetCamera();
        } catch (RuntimeException ignored) {
            // Continue cleanup even if the camera packet was rejected.
        }
        for (UUID actorId : List.copyOf(ghosts.keySet())) {
            try {
                remove(actorId);
            } catch (RuntimeException ignored) {
                // Best effort. Disconnect/world change also destroys client state.
            }
        }
        ghosts.clear();
    }
}
