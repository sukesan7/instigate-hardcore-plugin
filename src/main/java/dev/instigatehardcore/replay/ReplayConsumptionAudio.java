package dev.instigatehardcore.replay;

import java.util.List;

/** Bounded reconstructed bite/sip timestamps from a completed consumption. */
public final class ReplayConsumptionAudio {
    private ReplayConsumptionAudio() { }

    public static ReplayVisualEvent.Kind kindFor(String material, boolean edible) {
        if ("POTION".equals(material) || "SPLASH_POTION".equals(material)
            || "LINGERING_POTION".equals(material) || "MILK_BUCKET".equals(material)
            || "HONEY_BOTTLE".equals(material) || "OMINOUS_BOTTLE".equals(material)) {
            return ReplayVisualEvent.Kind.DRINK_SIP;
        }
        return edible ? ReplayVisualEvent.Kind.EAT_BITE : null;
    }

    public static List<Long> biteTicks(long finishTick) {
        // Approx. one 6-tick pulse, finishing 2 ticks before consumption.
        // Filter negative ticks on the first seconds of a fresh server.
        return java.util.stream.LongStream.of(
            finishTick - 24, finishTick - 18, finishTick - 12,
            finishTick - 6, finishTick - 2)
            .filter(t -> t >= 0).boxed().toList();
    }
}
