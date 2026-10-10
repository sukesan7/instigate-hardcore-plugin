package dev.instigatehardcore.replay;

/**
 * Client-side PLAYER ghost appearance for Minecraft Java 26.3.
 *
 * Player skin texture/model choice (classic or slim) comes from the signed
 * 'textures' profile property; the metadata bitmask controls whether the
 * outer parts of that texture are rendered.
 *
 * Keep the index scoped to this server protocol version. It is not suitable
 * as a cross-version metadata mapping.
 */
public final class ReplayPlayerSkinLayers {

    /** 26.3 Avatar.DATA_PLAYER_MODE_CUSTOMISATION metadata index. */
    public static final int METADATA_INDEX = 16;

    /** All seven supported visible skin sections. The 0x80 bit is reserved. */
    public static final byte ALL_VISIBLE = 0x7F;

    private ReplayPlayerSkinLayers() {
    }

    /** Only PLAYER ghosts use the Avatar skin-layer metadata. */
    public static boolean isPlayer(String entityType) {
        return "PLAYER".equalsIgnoreCase(entityType);
    }
}
