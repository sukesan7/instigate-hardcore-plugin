package dev.instigatehardcore.replay;

import java.util.Objects;
import java.util.UUID;

/**
 * One observed damage event in an ACTIVE attempt.
 * Contains values only; no mutable Bukkit Entity/Location references.
 */
public record ReplayCombatEvent(
    long tick,
    int attempt,
    UUID worldId,
    UUID damagedEntityId,
    String damagedEntityType,
    UUID attackerEntityId,
    String attackerEntityType,
    String damageCause,
    double finalDamage,
    double x,
    double y,
    double z
) {
    public ReplayCombatEvent {
        if (tick < 0 || attempt < 1) {
            throw new IllegalArgumentException("Invalid event tick or attempt.");
        }
        Objects.requireNonNull(worldId);
        Objects.requireNonNull(damagedEntityId);
        Objects.requireNonNull(damagedEntityType);
        Objects.requireNonNull(attackerEntityType);
        Objects.requireNonNull(damageCause);
        if (!Double.isFinite(finalDamage) || finalDamage < 0
            || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Combat event numbers must be finite and valid.");
        }
    }
}
