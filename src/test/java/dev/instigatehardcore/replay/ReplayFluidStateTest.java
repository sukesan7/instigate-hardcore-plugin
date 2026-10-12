package dev.instigatehardcore.replay;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReplayFluidStateTest {
    @Test void recordsWaterAndLavaSourcesAtBucketTime() {
        assertEquals("minecraft:water[level=0]",
            ReplayFluidState.placedSource("WATER_BUCKET", "minecraft:air"));
        assertEquals("minecraft:lava[level=0]",
            ReplayFluidState.placedSource("LAVA_BUCKET", "minecraft:cave_air"));
        assertNull(ReplayFluidState.placedSource("MILK_BUCKET", "minecraft:air"));
    }

    @Test void doesNotInventWaterloggingOrReplaceSolidBlocks() {
        assertNull(ReplayFluidState.placedSource("WATER_BUCKET", "minecraft:oak_stairs[facing=north]"));
        assertNull(ReplayFluidState.placedSource("WATER_BUCKET", "minecraft:stone"));
    }

    @Test void bucketPickupRemovesOnlyNormalSources() {
        assertEquals("minecraft:air", ReplayFluidState.pickedUpSource("minecraft:water[level=0]"));
        assertEquals("minecraft:air", ReplayFluidState.pickedUpSource("minecraft:lava[level=0]"));
        assertNull(ReplayFluidState.pickedUpSource("minecraft:water[level=3]"));
        assertNull(ReplayFluidState.pickedUpSource("minecraft:oak_stairs[waterlogged=true]"));
    }

    @Test void identifiesFlowingFluidStates() {
        assertTrue(ReplayFluidState.isFluid("minecraft:water[level=7]"));
        assertTrue(ReplayFluidState.isFluid("minecraft:lava[level=4]"));
        assertFalse(ReplayFluidState.isFluid("minecraft:air"));
    }
}
