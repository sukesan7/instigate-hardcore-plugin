package dev.instigatehardcore.replay;

/** Server-side safety limits for optional cosmetic replay effects. */
public final class ReplayEffectBudget {
    public static final int DEFAULT_PARTICLES_PER_TICK = 24;
    public static final int MAX_PARTICLES_PER_TICK = 64;

    private ReplayEffectBudget() { }

    /**
     * The movement effect planner requires a positive limit when enabled.
     * Zero and negatives fall back to the default; exceptionally large
     * configuration values cannot cause unbounded particles per viewer.
     */
    public static int movementParticlesPerTick(int configured) {
        return configured <= 0 ? DEFAULT_PARTICLES_PER_TICK
            : Math.min(MAX_PARTICLES_PER_TICK, configured);
    }
}
