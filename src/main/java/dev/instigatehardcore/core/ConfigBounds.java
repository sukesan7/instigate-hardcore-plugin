package dev.instigatehardcore.core;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * Centralized numeric bounds and compatibility resolvers for config.yml.
 *
 * Out-of-range configuration values log a clear warning and fall back to safe
 * production defaults instead of crashing plugin startup or wedging gameplay.
 */
public final class ConfigBounds {

    public static final int DEFAULT_COUNTDOWN_SECONDS = 10;
    public static final int MIN_COUNTDOWN_SECONDS = 1;
    public static final int MAX_COUNTDOWN_SECONDS = 600;

    public static final int DEFAULT_PRELOAD_RADIUS_CHUNKS = 1;
    public static final int MIN_PRELOAD_RADIUS_CHUNKS = 0;
    public static final int MAX_PRELOAD_RADIUS_CHUNKS = 8;

    public static final long DEFAULT_SCOREBOARD_UPDATE_TICKS = 20L;
    public static final long MIN_SCOREBOARD_UPDATE_TICKS = 1L;
    public static final long MAX_SCOREBOARD_UPDATE_TICKS = 1200L;

    public static final int DEFAULT_SCOREBOARD_PAGE_SECONDS = 10;
    public static final int MIN_SCOREBOARD_PAGE_SECONDS = 1;
    public static final int MAX_SCOREBOARD_PAGE_SECONDS = 300;

    public static final int DEFAULT_TAB_HEALTH_UPDATE_TICKS = 10;
    public static final int MIN_TAB_HEALTH_UPDATE_TICKS = 1;
    public static final int MAX_TAB_HEALTH_UPDATE_TICKS = 1200;

    public static final int DEFAULT_REPLAY_DURATION_SECONDS = 7;
    public static final int MIN_REPLAY_DURATION_SECONDS = 1;
    public static final int MAX_REPLAY_DURATION_SECONDS = 30;

    public static final int DEFAULT_REPLAY_CAPTURE_INTERVAL_TICKS = 2;
    public static final int MIN_REPLAY_CAPTURE_INTERVAL_TICKS = 1;
    public static final int MAX_REPLAY_CAPTURE_INTERVAL_TICKS = 20;

    public static final int DEFAULT_REPLAY_CAPTURE_RADIUS_BLOCKS = 24;
    public static final int MIN_REPLAY_CAPTURE_RADIUS_BLOCKS = 1;
    public static final int MAX_REPLAY_CAPTURE_RADIUS_BLOCKS = 64;

    public static final int DEFAULT_REPLAY_MAX_ACTORS_PER_FRAME = 48;
    public static final int MIN_REPLAY_MAX_ACTORS_PER_FRAME = 1;
    public static final int MAX_REPLAY_MAX_ACTORS_PER_FRAME = 128;

    public static final int DEFAULT_TERRAIN_MAX_CHANGES_PER_CLIP = 1024;
    public static final int MIN_TERRAIN_MAX_CHANGES_PER_CLIP = 1;
    public static final int MAX_TERRAIN_MAX_CHANGES_PER_CLIP = 4096;

    public static final int DEFAULT_TERRAIN_MAX_WATCHED_BLOCKS = 512;
    public static final int MIN_TERRAIN_MAX_WATCHED_BLOCKS = 1;
    public static final int MAX_TERRAIN_MAX_WATCHED_BLOCKS = 2048;

    public static final int DEFAULT_TERRAIN_MAX_READS_PER_TICK = 128;
    public static final int MIN_TERRAIN_MAX_READS_PER_TICK = 1;
    public static final int MAX_TERRAIN_MAX_READS_PER_TICK = 512;

    public static final double MAX_LOW_HEALTH_HEARTS = 100.0;

    public static final String DEPRECATED_RESOURCE_PACK_HUD_KEY =
        "death-replay.cinematic.resource-pack-hud-enabled";

    private ConfigBounds() {
    }

    public static int boundedInt(
        int configured,
        int min,
        int max,
        int fallback,
        String key,
        Logger logger
    ) {
        if (configured < min || configured > max) {
            if (logger != null) {
                logger.warning(
                    "Invalid " + key + " value: " + configured
                        + " (allowed " + min + ".." + max + "). Using " + fallback + "."
                );
            }
            return fallback;
        }
        return configured;
    }

    public static long boundedLong(
        long configured,
        long min,
        long max,
        long fallback,
        String key,
        Logger logger
    ) {
        if (configured < min || configured > max) {
            if (logger != null) {
                logger.warning(
                    "Invalid " + key + " value: " + configured
                        + " (allowed " + min + ".." + max + "). Using " + fallback + "."
                );
            }
            return fallback;
        }
        return configured;
    }

    public static boolean validLowHealthThresholds(
        double warning,
        double warningRearm,
        double critical,
        double criticalRearm
    ) {
        return Double.isFinite(warning)
            && Double.isFinite(warningRearm)
            && Double.isFinite(critical)
            && Double.isFinite(criticalRearm)
            && critical > 0.0
            && criticalRearm > critical
            && warning > critical
            && warningRearm > warning
            && warningRearm <= MAX_LOW_HEALTH_HEARTS;
    }

    /**
     * Supports both the canonical key {@code death-replay.audio.enabled} and
     * the legacy nested key {@code death-replay.terrain.audio.enabled} from
     * earlier shipped config.yml files.
     */
    public static boolean resolveReplayAudioEnabled(FileConfiguration config) {
        Objects.requireNonNull(config, "config");
        if (config.isBoolean("death-replay.audio.enabled")) {
            return config.getBoolean("death-replay.audio.enabled", true);
        }
        if (config.isBoolean("death-replay.terrain.audio.enabled")) {
            return config.getBoolean("death-replay.terrain.audio.enabled", true);
        }
        return true;
    }

    /**
     * Emits a single concise startup warning when a legacy configuration still
     * enables the retired experimental resource-pack HUD option. The option is
     * always ignored so standard Adventure Deathcam rendering is used.
     */
    public static boolean warnIfDeprecatedResourcePackHudEnabled(
        FileConfiguration config,
        Logger logger
    ) {
        Objects.requireNonNull(config, "config");
        if (!config.getBoolean(DEPRECATED_RESOURCE_PACK_HUD_KEY, false)) {
            return false;
        }
        if (logger != null) {
            logger.warning(
                "Configuration option '" + DEPRECATED_RESOURCE_PACK_HUD_KEY
                    + "' is deprecated and ignored; the experimental resource-pack HUD "
                    + "has been retired and the standard Deathcam renderer will be used."
            );
        }
        return true;
    }
}
