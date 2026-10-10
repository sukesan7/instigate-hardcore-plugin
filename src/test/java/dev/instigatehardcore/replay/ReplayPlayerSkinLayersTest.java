package dev.instigatehardcore.replay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayPlayerSkinLayersTest {

    @Test
    void enablesEveryVisibleSkinSectionWithoutReservedBit() {
        assertEquals(0x7F, Byte.toUnsignedInt(ReplayPlayerSkinLayers.ALL_VISIBLE));
        assertEquals(0, Byte.toUnsignedInt(ReplayPlayerSkinLayers.ALL_VISIBLE) & 0x80);
    }

    @Test
    void usesMinecraft26Point3AvatarMetadataIndex() {
        assertEquals(16, ReplayPlayerSkinLayers.METADATA_INDEX);
    }

    @Test
    void doesNotSendPlayerMetadataToMobs() {
        assertTrue(ReplayPlayerSkinLayers.isPlayer("PLAYER"));
        assertTrue(ReplayPlayerSkinLayers.isPlayer("player"));
        assertFalse(ReplayPlayerSkinLayers.isPlayer("ZOMBIE"));
        assertFalse(ReplayPlayerSkinLayers.isPlayer("SKELETON"));
        assertFalse(ReplayPlayerSkinLayers.isPlayer(null));
    }
}
