package dev.instigatehardcore.replay;

import java.util.Locale;
import java.util.Objects;

/** Visual item material carried by a replayed dropped ITEM actor. */
public final class ReplayDroppedItemState {
    private ReplayDroppedItemState() { }

    public static boolean isDroppedItem(String entityType) {
        if (entityType == null) return false;
        String type = entityType.toUpperCase(Locale.ROOT);
        return type.equals("ITEM") || type.equals("DROPPED_ITEM");
    }

    public static ReplayEquipment withMaterial(String material) {
        Objects.requireNonNull(material);
        if (material.isBlank()) throw new IllegalArgumentException("Empty item material");
        return new ReplayEquipment(material, "AIR", "AIR", "AIR", "AIR", "AIR");
    }
}
