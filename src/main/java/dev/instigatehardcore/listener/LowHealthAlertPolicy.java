package dev.instigatehardcore.listener;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Pure state machine: alert once on danger, re-arm after meaningful healing. */
public final class LowHealthAlertPolicy {
    private final double thresholdHealth;
    private final double rearmHealth;
    private final Set<UUID> alerted = new HashSet<>();

    public LowHealthAlertPolicy(double thresholdHearts, double rearmHearts) {
        if (!Double.isFinite(thresholdHearts) || thresholdHearts <= 0
                || !Double.isFinite(rearmHearts) || rearmHearts <= thresholdHearts) {
            throw new IllegalArgumentException("Expected 0 < threshold hearts < rearm hearts");
        }
        this.thresholdHealth = thresholdHearts * 2.0;
        this.rearmHealth = rearmHearts * 2.0;
    }

    public boolean check(UUID player, double health, boolean eligible) {
        if (!eligible) {
            return false;
        }
        if (health >= rearmHealth) {
            alerted.remove(player);
            return false;
        }
        return health > 0.0 && health < thresholdHealth && alerted.add(player);
    }

    public void clear() {
        alerted.clear();
    }
}
