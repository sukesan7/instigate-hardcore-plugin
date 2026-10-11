package dev.instigatehardcore.replay;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemSwingAnimation;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
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
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHurtAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSwingAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
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
 * It does not rewind blocks or replay environmental damage sounds.
 * Phase 9F.2 adds ghost-only combat animation packets.
 */
public final class ReplayPacketActorTransport implements ReplayActorTransport {
    private record Ghost(int entityId, UUID profileId, boolean player, ReplayActorPose lastPose) { }

    private final Player viewer;
    private final UUID replayWorldId;
    private final Map<UUID, Ghost> ghosts = new LinkedHashMap<>();
    private final Set<UUID> detonatedCreepers = new HashSet<>();
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
                case SPAWN -> {
                    if (!detonatedCreepers.contains(change.actorId())) spawn(change.pose());
                }
                case UPDATE -> {
                    if (!detonatedCreepers.contains(change.actorId())) update(change.pose());
                }
                case REMOVE -> remove(change.actorId());
            }
        }
    }

    /**
     * Apply only recorded, time-aligned visual events to fake ghost IDs.
     * Events referencing actors not currently visible are ignored.
     */
    @Override
    public void playVisualEvents(List<ReplayVisualEvent> events) {
        assertMainThread();
        if (closed || !viewer.isOnline()
            || !viewer.getWorld().getUID().equals(replayWorldId)) {
            return;
        }
        for (ReplayVisualEvent event : Objects.requireNonNull(events)) {
            if (!event.worldId().equals(replayWorldId)) {
                continue;
            }
            Ghost ghost = ghosts.get(event.actorId());
            if (ghost == null) {
                continue; // The actor is absent from the recorded frame.
            }
            switch (event.kind()) {
                case SWING_MAIN -> send(new WrapperPlayServerSwingAnimation(
                    ghost.entityId(), InteractionHand.MAIN_HAND,
                    new ItemSwingAnimation(ItemSwingAnimation.Type.WHACK, 6)
                ));
                case SWING_OFF -> send(new WrapperPlayServerSwingAnimation(
                    ghost.entityId(), InteractionHand.OFF_HAND,
                    new ItemSwingAnimation(ItemSwingAnimation.Type.WHACK, 6)
                ));
                case HURT -> send(new WrapperPlayServerHurtAnimation(
                    ghost.entityId(), ghost.lastPose().yaw()
                ));
                case CRITICAL -> send(new WrapperPlayServerEntityAnimation(
                    ghost.entityId(),
                    WrapperPlayServerEntityAnimation.EntityAnimationType.CRITICAL_HIT
                ));
            }
        }
    }

    /** Viewer-only explosion particles/sound; never call world.createExplosion(). */
    @Override
    public void playCreeperExplosions(List<ReplayCreeperExplosionEvent> events) {
        assertMainThread();
        if (closed || !viewer.isOnline()
            || !viewer.getWorld().getUID().equals(replayWorldId)) return;
        int effectsShown = 0;
        for (ReplayCreeperExplosionEvent event : Objects.requireNonNull(events)) {
            if (!event.worldId().equals(replayWorldId)
                || !detonatedCreepers.add(event.creeperId())) continue;
            // Always retire the ghost, even when the visual budget is exhausted.
            remove(event.creeperId());
            if (effectsShown++ >= 6) continue; // Per-viewer, per-tick cap.
            double x = event.x();
            double y = event.y() + 0.45;
            double z = event.z();
            // Spectator-only effects, never persisted to the real world.
            viewer.spawnParticle(
                Particle.EXPLOSION_EMITTER, x, y, z, 1, 0, 0, 0, 0
            );
            if (event.powered()) {
                viewer.spawnParticle(
                    Particle.ELECTRIC_SPARK, x, y, z,
                    18, 0.55, 0.55, 0.55, 0.08
                );
            }
            viewer.playSound(
                new org.bukkit.Location(viewer.getWorld(), x, y, z),
                Sound.ENTITY_GENERIC_EXPLODE, 1.0f, event.powered() ? 0.8f : 1.0f
            );
        }
    }

    /** Display only recorded movement effects to this replay's spectator. */
    @Override
    public void playMovementEffects(List<ReplayMovementEffect> effects) {
        assertMainThread();
        if (closed || !viewer.isOnline()
            || !viewer.getWorld().getUID().equals(replayWorldId)) {
            return;
        }
        for (ReplayMovementEffect effect : Objects.requireNonNull(effects)) {
            ReplayActorPose pose = effect.pose();
            Ghost ghost = ghosts.get(pose.id());
            if (ghost == null || !ghost.player()) {
                continue;
            }
            Material ground = Material.matchMaterial(pose.groundMaterial());
            if (ground == null || !ground.isBlock() || !ground.isSolid()) {
                continue; // Air, water and unsupported materials emit no block dust.
            }
            try {
                var blockData = ground.createBlockData();
                double spread = effect.kind() == ReplayMovementEffect.Kind.LANDING_DUST
                    ? 0.32 : 0.16;
                viewer.spawnParticle(
                    Particle.BLOCK_CRUMBLE,
                    pose.x(), pose.y() + 0.06, pose.z(),
                    effect.particleCount(), spread, 0.05, spread,
                    0.015, blockData
                );
            } catch (IllegalArgumentException exception) {
                // Unusual material/block data: omit particles, never break replay.
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
        if (isCreeper(pose) && pose.creeperState().swelling()) {
            playCreeperPrime(pose);
        }
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
            || ghost.lastPose().gliding() != pose.gliding()
            || ghost.lastPose().sprinting() != pose.sprinting()
            || (isCreeper(pose) && !ghost.lastPose().creeperState()
                .sameMetadata(pose.creeperState()))) {
            sendMetadata(ghost.entityId(), pose);
        }
        if (isCreeper(pose) && !ghost.lastPose().creeperState().swelling()
            && pose.creeperState().swelling()) {
            playCreeperPrime(pose);
        }
        if (!ghost.lastPose().equipment().equals(pose.equipment())) {
            sendEquipment(ghost.entityId(), pose.equipment());
        }
        ghosts.put(pose.id(), new Ghost(ghost.entityId(), ghost.profileId(), ghost.player(), pose));
    }

    private void sendMetadata(int id, ReplayActorPose pose) {
        // Shared entity flags: fire 0x01, crouch 0x02, sprint 0x08, glide 0x80.
        int flags = (pose.burning() ? 0x01 : 0)
            | (pose.sneaking() ? 0x02 : 0)
            | (pose.sprinting() ? 0x08 : 0)
            | (pose.gliding() ? 0x80 : 0);

        List<EntityData<?>> metadata = new ArrayList<>(2);
        metadata.add(new EntityData<>(0, EntityDataTypes.BYTE, (byte) flags));

        // Minecraft 26.3: Avatar skin customisation is metadata index 16.
        // The default for a newly spawned fake PLAYER is 0 (inner skin only).
        // 0x7F enables cape, jacket, both sleeves, both trouser overlays and hat.
        // This is only valid for PLAYER actors: never put Avatar metadata on mobs.
        if (ReplayPlayerSkinLayers.isPlayer(pose.entityType())) {
            metadata.add(new EntityData<>(
                ReplayPlayerSkinLayers.METADATA_INDEX,
                EntityDataTypes.BYTE,
                ReplayPlayerSkinLayers.ALL_VISIBLE
            ));
        }

        // Creeper-specific metadata in Minecraft 26.3: swelling direction,
        // charged appearance and explicitly ignited fuse. These are NOT
        // player metadata, despite overlapping index numbers.
        if (isCreeper(pose)) {
            ReplayCreeperState state = pose.creeperState();
            metadata.add(new EntityData<>(16, EntityDataTypes.INT,
                state.swelling() ? 1 : -1));
            metadata.add(new EntityData<>(17, EntityDataTypes.BOOLEAN, state.powered()));
            metadata.add(new EntityData<>(18, EntityDataTypes.BOOLEAN, state.ignited()));
        }

        // Called at spawn and on state changes, so overlay flags stay consistent.
        send(new WrapperPlayServerEntityMetadata(id, metadata));
    }

    private static boolean isCreeper(ReplayActorPose pose) {
        return "CREEPER".equalsIgnoreCase(pose.entityType());
    }

    private void playCreeperPrime(ReplayActorPose pose) {
        viewer.playSound(
            new org.bukkit.Location(viewer.getWorld(), pose.x(), pose.y(), pose.z()),
            Sound.ENTITY_CREEPER_PRIMED, 0.75f, 1.0f
        );
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
        detonatedCreepers.clear();
    }
}
