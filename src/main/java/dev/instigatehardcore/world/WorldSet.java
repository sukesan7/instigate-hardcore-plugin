package dev.instigatehardcore.world;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.Objects;

public record WorldSet(
    int attemptNumber,
    long seed,
    World overworld,
    World nether,
    World end
) {

    public WorldSet {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException(
                "Attempt number must be at least one."
            );
        }

        Objects.requireNonNull(overworld);
        Objects.requireNonNull(nether);
        Objects.requireNonNull(end);
    }

    public boolean contains(World world) {
        if (world == null) {
            return false;
        }

        return world.equals(overworld)
            || world.equals(nether)
            || world.equals(end);
    }

    /**
     * Returns the spawn that players will eventually use when
     * this WorldSet becomes active.
     *
     * Phase 6C will perform additional safety checks before
     * teleporting players here.
     */
    public Location getSpawnLocation() {
        return overworld
            .getSpawnLocation()
            .clone()
            .add(0.5, 0.0, 0.5);
    }
}