package dev.instigatehardcore.replay;

/** Burning status for fake actors, including contact with fire/lava. */
public final class ReplayFireVisualState {
    private ReplayFireVisualState() { }

    public static boolean isBurning(boolean hasFireTicks, String feet, String torso) {
        return hasFireTicks || isHotBlock(feet) || isHotBlock(torso);
    }

    private static boolean isHotBlock(String material) {
        return "FIRE".equals(material) || "SOUL_FIRE".equals(material)
            || "LAVA".equals(material);
    }
}
