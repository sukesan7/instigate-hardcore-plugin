package dev.instigatehardcore.replay;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReplayEffectBudgetTest {
    @Test void normalSettingIsUnchanged() {
        assertEquals(24, ReplayEffectBudget.movementParticlesPerTick(24));
    }
    @Test void configuredBudgetCannotGrowWithoutBound() {
        assertEquals(64, ReplayEffectBudget.movementParticlesPerTick(Integer.MAX_VALUE));
    }
    @Test void nonPositiveBudgetFallsBackToDefault() {
        assertEquals(24, ReplayEffectBudget.movementParticlesPerTick(0));
        assertEquals(24, ReplayEffectBudget.movementParticlesPerTick(-100));
    }
    @Test void lowPositiveBudgetRemainsAvailable() {
        assertEquals(1, ReplayEffectBudget.movementParticlesPerTick(1));
    }
}
