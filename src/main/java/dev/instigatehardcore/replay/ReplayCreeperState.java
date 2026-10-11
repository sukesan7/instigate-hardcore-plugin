package dev.instigatehardcore.replay;

/** Creeper-specific state sampled with existing actor keyframes. */
public record ReplayCreeperState(
    int fuseTicks, int maxFuseTicks, boolean ignited, boolean powered
) {
    public static final ReplayCreeperState NONE = new ReplayCreeperState(0, 30, false, false);

    public ReplayCreeperState {
        if (fuseTicks < 0 || maxFuseTicks < 1) {
            throw new IllegalArgumentException("Creeper fuse ticks must be non-negative and max > 0.");
        }
    }

    /** Natural target-triggered fuses need not have the permanent ignited flag. */
    public boolean swelling() {
        return fuseTicks > 0 || ignited;
    }

    /** Only these values affect client metadata; fuseTicks itself advances client-side. */
    public boolean sameMetadata(ReplayCreeperState other) {
        return other != null && swelling() == other.swelling()
            && ignited == other.ignited && powered == other.powered;
    }
}
