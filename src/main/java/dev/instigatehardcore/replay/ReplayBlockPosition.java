package dev.instigatehardcore.replay;

/** Integer block location, detached from Bukkit objects for replay snapshots. */
public record ReplayBlockPosition(int x, int y, int z) { }
