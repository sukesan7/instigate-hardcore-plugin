package dev.instigatehardcore.qol;

import dev.instigatehardcore.listener.TieredHealthAlertPolicy;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static dev.instigatehardcore.listener.TieredHealthAlertPolicy.Level.*;
import static org.junit.jupiter.api.Assertions.*;

class TieredHealthAlertPolicyTest {
    private final UUID id = UUID.randomUUID();

    @Test void warnsOnlyWhenCrossingBelowFive() {
        var p = new TieredHealthAlertPolicy(5, 6, 2, 3);
        assertEquals(NONE, p.evaluate(id, 20, true));
        assertEquals(NONE, p.evaluate(id, 10, true));
        assertEquals(WARNING, p.evaluate(id, 9.9, true));
        assertEquals(NONE, p.evaluate(id, 9.0, true));
        assertEquals(NONE, p.evaluate(id, 11.9, true));
        assertEquals(NONE, p.evaluate(id, 12.0, true));
        assertEquals(WARNING, p.evaluate(id, 8.0, true));
    }

    @Test void criticalAlarmOnePerEpisodeWithIndependentRearm() {
        var p = new TieredHealthAlertPolicy(5, 6, 2, 3);
        assertEquals(WARNING, p.evaluate(id, 7.0, true));
        assertEquals(CRITICAL, p.evaluate(id, 3.0, true));
        assertEquals(NONE, p.evaluate(id, 2.0, true));
        assertEquals(NONE, p.evaluate(id, 6.0, true));
        assertEquals(CRITICAL, p.evaluate(id, 3.0, true));
        assertEquals(NONE, p.evaluate(id, 4.0, true));
    }

    @Test void straightToCriticalSkipsOrangeAndRearmsAfterSix() {
        var p = new TieredHealthAlertPolicy(5, 6, 2, 3);
        assertEquals(CRITICAL, p.evaluate(id, 2.0, true));
        assertEquals(NONE, p.evaluate(id, 8.0, true));
        assertEquals(NONE, p.evaluate(id, 12.0, true));
        assertEquals(WARNING, p.evaluate(id, 8.0, true));
    }

    @Test void outsideAttemptAndDeathCannotTriggerAlerts() {
        var p = new TieredHealthAlertPolicy(5, 6, 2, 3);
        assertEquals(NONE, p.evaluate(id, 1.0, false));
        assertEquals(NONE, p.evaluate(id, 0.0, true));
        assertEquals(CRITICAL, p.evaluate(id, 1.0, true));
        p.clear();
        assertEquals(CRITICAL, p.evaluate(id, 1.0, true));
    }

    @Test void independentPlayers() {
        var p = new TieredHealthAlertPolicy(5, 6, 2, 3);
        assertEquals(WARNING, p.evaluate(id, 9.0, true));
        assertEquals(WARNING, p.evaluate(UUID.randomUUID(), 9.0, true));
    }
}
