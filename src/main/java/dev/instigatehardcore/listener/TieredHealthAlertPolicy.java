package dev.instigatehardcore.listener;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Two independent low-health thresholds; inputs are Bukkit health points.
 * Exactly 2 and 5 hearts are NOT below their respective thresholds.
 */
public final class TieredHealthAlertPolicy {
    public enum Level { NONE, WARNING, CRITICAL }

    private final double warningHealth;
    private final double warningRearmHealth;
    private final double criticalHealth;
    private final double criticalRearmHealth;
    private final Set<UUID> warned = new HashSet<>();
    private final Set<UUID> critical = new HashSet<>();

    public TieredHealthAlertPolicy(double warningHearts, double warningRearmHearts,
                                   double criticalHearts, double criticalRearmHearts) {
        if (!Double.isFinite(warningHearts) || !Double.isFinite(warningRearmHearts)
                || !Double.isFinite(criticalHearts) || !Double.isFinite(criticalRearmHearts)
                || criticalHearts <= 0 || criticalRearmHearts <= criticalHearts
                || warningHearts <= criticalHearts || warningRearmHearts <= warningHearts) {
            throw new IllegalArgumentException("Invalid health alert thresholds");
        }
        this.warningHealth = warningHearts * 2.0;
        this.warningRearmHealth = warningRearmHearts * 2.0;
        this.criticalHealth = criticalHearts * 2.0;
        this.criticalRearmHealth = criticalRearmHearts * 2.0;
    }

    public Level evaluate(UUID id, double health, boolean eligible) {
        if (!eligible) {
            forget(id);
            return Level.NONE;
        }
        if (!Double.isFinite(health) || health <= 0) return Level.NONE;

        if (health >= warningRearmHealth) warned.remove(id);
        if (health >= criticalRearmHealth) critical.remove(id);

        if (health < criticalHealth) {
            // A sudden drop straight into critical range should NOT also send orange.
            warned.add(id);
            return critical.add(id) ? Level.CRITICAL : Level.NONE;
        }
        if (health < warningHealth) {
            return warned.add(id) ? Level.WARNING : Level.NONE;
        }
        return Level.NONE;
    }

    public void forget(UUID id) {
        warned.remove(id);
        critical.remove(id);
    }

    public void clear() {
        warned.clear();
        critical.clear();
    }
}
