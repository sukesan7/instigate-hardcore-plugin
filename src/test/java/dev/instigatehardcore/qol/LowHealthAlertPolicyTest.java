package dev.instigatehardcore.qol;

import dev.instigatehardcore.listener.LowHealthAlertPolicy;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class LowHealthAlertPolicyTest {
    private final UUID player = UUID.randomUUID();

    @Test void warnsOnceWhenCrossingBelowTwoHearts() {
        var policy = new LowHealthAlertPolicy(2.0, 3.0);
        assertFalse(policy.check(player, 4.0, true));
        assertTrue(policy.check(player, 3.9, true));
        assertFalse(policy.check(player, 1.0, true));
        assertFalse(policy.check(player, 0.0, true));
    }

    @Test void rearmsAtThreeHearts() {
        var policy = new LowHealthAlertPolicy(2.0, 3.0);
        assertTrue(policy.check(player, 2.0, true));
        assertFalse(policy.check(player, 5.0, true));
        assertFalse(policy.check(player, 6.0, true));
        assertTrue(policy.check(player, 3.0, true));
    }

    @Test void suppressesOutsideActiveWorld() {
        var policy = new LowHealthAlertPolicy(2.0, 3.0);
        assertFalse(policy.check(player, 1.0, false));
        assertTrue(policy.check(player, 1.0, true));
        policy.clear();
        assertTrue(policy.check(player, 1.0, true));
    }
}
