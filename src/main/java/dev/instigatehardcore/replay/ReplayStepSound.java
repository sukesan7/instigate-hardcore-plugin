package dev.instigatehardcore.replay;

import org.bukkit.Sound;

import java.util.Locale;

/** Broad sound-group approximation using recorded ground material. */
public final class ReplayStepSound {
    private ReplayStepSound() { }

    public static Sound material(String ground) {
        if (ground == null) return null;
        String g = ground.toUpperCase(Locale.ROOT);
        if (g.equals("AIR") || g.equals("CAVE_AIR") || g.equals("VOID_AIR")
            || g.contains("WATER") || g.contains("LAVA")) return null;
        String key;
        if (g.contains("GRASS") || g.contains("DIRT") || g.contains("MOSS")
            || g.contains("PODZOL") || g.contains("MYCELIUM")) key = "BLOCK_GRASS_STEP";
        else if (g.contains("SAND") || g.contains("GRAVEL")) key = "BLOCK_SAND_STEP";
        else if (g.contains("WOOL") || g.contains("CARPET")) key = "BLOCK_WOOL_STEP";
        else if (g.contains("LOG") || g.contains("PLANKS") || g.contains("WOOD")
            || g.contains("BAMBOO")) key = "BLOCK_WOOD_STEP";
        else if (g.contains("SNOW")) key = "BLOCK_SNOW_STEP";
        else key = "BLOCK_STONE_STEP";
        try { return Sound.valueOf(key); }
        catch (IllegalArgumentException unsupported) { return null; }
    }
}
