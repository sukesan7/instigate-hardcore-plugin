package dev.instigatehardcore.replay;

import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose;

/** Packet-only pose metadata for modern vanilla clients. */
public final class ReplayGhostPose {
    private ReplayGhostPose() { }

    public static EntityPose of(boolean sneaking, boolean gliding) {
        if (gliding) return EntityPose.FALL_FLYING;
        return sneaking ? EntityPose.CROUCHING : EntityPose.STANDING;
    }
}
