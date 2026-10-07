package dev.instigatehardcore.stats;

import java.util.UUID;

public record PlayerStats(
    UUID uuid,
    String name,
    int deaths
) {
}