package dev.instigatehardcore.replay;

import java.util.Objects;

/**
 * The six vanilla equipment material names visible on a recorded actor.
 * An empty slot is represented by AIR. This is intentionally independent
 * of Bukkit ItemStack so buffered frames cannot be mutated later.
 */
public record ReplayEquipment(
    String mainHand,
    String offHand,
    String helmet,
    String chestplate,
    String leggings,
    String boots
) {
    public ReplayEquipment {
        Objects.requireNonNull(mainHand);
        Objects.requireNonNull(offHand);
        Objects.requireNonNull(helmet);
        Objects.requireNonNull(chestplate);
        Objects.requireNonNull(leggings);
        Objects.requireNonNull(boots);
    }

    public static ReplayEquipment empty() {
        return new ReplayEquipment("AIR", "AIR", "AIR", "AIR", "AIR", "AIR");
    }
}
