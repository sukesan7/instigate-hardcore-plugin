package dev.instigatehardcore.replay;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ReplayDroppedItemStateTest {
    @Test
    void itemActorsAreRecognizedWithoutMarkingLivingEntitiesAsItems() {
        assertTrue(ReplayDroppedItemState.isDroppedItem("ITEM"));
        assertTrue(ReplayDroppedItemState.isDroppedItem("dropped_item"));
        assertFalse(ReplayDroppedItemState.isDroppedItem("CREEPER"));
    }

    @Test
    void retainsDroppedMaterialInReplayVisualSlot() {
        ReplayEquipment equipment = ReplayDroppedItemState.withMaterial("DIAMOND");
        assertEquals("DIAMOND", equipment.mainHand());
        assertEquals("AIR", equipment.helmet());
    }
}
