package dev.instigatehardcore.replay;

import java.util.Locale;
import java.util.Objects;

/** Pure fluid source transition rules; unusual blocks use actual next-tick reads. */
public final class ReplayFluidState {
    private ReplayFluidState() { }

    public static boolean isFluid(String data) {
        return data != null && (data.startsWith("minecraft:water[")
            || data.equals("minecraft:water")
            || data.startsWith("minecraft:lava[")
            || data.equals("minecraft:lava"));
    }

    private static boolean isAir(String data) {
        return "minecraft:air".equals(data) || "minecraft:cave_air".equals(data)
            || "minecraft:void_air".equals(data);
    }

    /** Only ordinary bucket placement into empty/air blocks is predictable. */
    public static String placedSource(String bucket, String before) {
        Objects.requireNonNull(bucket);
        if (!isAir(before)) return null;
        return switch (bucket.toUpperCase(Locale.ROOT)) {
            case "WATER_BUCKET" -> "minecraft:water[level=0]";
            case "LAVA_BUCKET" -> "minecraft:lava[level=0]";
            default -> null;
        };
    }

    /** Filled buckets remove vanilla fluid sources, but not flowing fluid. */
    public static String pickedUpSource(String before) {
        return "minecraft:water[level=0]".equals(before)
            || "minecraft:lava[level=0]".equals(before)
            || "minecraft:water".equals(before)
            || "minecraft:lava".equals(before)
            ? "minecraft:air" : null;
    }
}
