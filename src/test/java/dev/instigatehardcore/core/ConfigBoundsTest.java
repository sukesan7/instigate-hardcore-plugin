package dev.instigatehardcore.core;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ConfigBoundsTest {

    @Test
    void shippedConfigHasProductionSafeDefaults() {
        var stream = ConfigBoundsTest.class.getResourceAsStream("/config.yml");
        assertNotNull(stream, "Shipped config.yml must be present on classpath");

        YamlConfiguration config = YamlConfiguration.loadConfiguration(
            new InputStreamReader(stream, StandardCharsets.UTF_8)
        );

        assertFalse(
            config.getBoolean("death-replay.debug-preview-enabled", true),
            "Replay debug preview must be disabled by default in production"
        );
        assertTrue(
            config.getBoolean("death-replay.enabled", false),
            "Deathcam recording must stay enabled by default"
        );
        assertTrue(
            config.getBoolean("death-replay.automatic-playback-enabled", false),
            "Normal Deathcam playback must stay enabled by default"
        );
        assertFalse(
            config.contains(ConfigBounds.DEPRECATED_RESOURCE_PACK_HUD_KEY),
            "Retired resource-pack HUD option must not be present in shipped config.yml"
        );
        assertFalse(
            ConfigBounds.warnIfDeprecatedResourcePackHudEnabled(config, null),
            "Fresh config must not trigger deprecated resource-pack HUD warning"
        );
        assertTrue(
            ConfigBounds.resolveReplayAudioEnabled(config),
            "Deathcam audio must stay enabled by default"
        );
        assertEquals(10, config.getInt("reset.countdown-seconds"));
        assertEquals("world", config.getString("worlds.lobby-world"));
        assertEquals(1, config.getInt("worlds.preload-radius-chunks"));
    }

    @Test
    void boundedNumericHelpersRejectOutOfRangeValues() {
        assertEquals(10, ConfigBounds.boundedInt(0, 1, 600, 10, "reset.countdown-seconds", null));
        assertEquals(10, ConfigBounds.boundedInt(601, 1, 600, 10, "reset.countdown-seconds", null));
        assertEquals(30, ConfigBounds.boundedInt(30, 1, 600, 10, "reset.countdown-seconds", null));

        assertEquals(1, ConfigBounds.boundedInt(-1, 0, 8, 1, "worlds.preload-radius-chunks", null));
        assertEquals(1, ConfigBounds.boundedInt(9, 0, 8, 1, "worlds.preload-radius-chunks", null));
        assertEquals(0, ConfigBounds.boundedInt(0, 0, 8, 1, "worlds.preload-radius-chunks", null));

        assertEquals(20L, ConfigBounds.boundedLong(0L, 1L, 1200L, 20L, "scoreboard.update-interval-ticks", null));
        assertEquals(20L, ConfigBounds.boundedLong(5000L, 1L, 1200L, 20L, "scoreboard.update-interval-ticks", null));
        assertEquals(40L, ConfigBounds.boundedLong(40L, 1L, 1200L, 20L, "scoreboard.update-interval-ticks", null));
    }

    @Test
    void lowHealthThresholdValidationRejectsNonFiniteAndUnorderedValues() {
        assertTrue(ConfigBounds.validLowHealthThresholds(5.0, 6.0, 2.0, 3.0));
        assertFalse(ConfigBounds.validLowHealthThresholds(Double.NaN, 6.0, 2.0, 3.0));
        assertFalse(ConfigBounds.validLowHealthThresholds(5.0, Double.POSITIVE_INFINITY, 2.0, 3.0));
        assertFalse(ConfigBounds.validLowHealthThresholds(5.0, 150.0, 2.0, 3.0));
        assertFalse(ConfigBounds.validLowHealthThresholds(2.0, 6.0, 2.0, 3.0));
        assertFalse(ConfigBounds.validLowHealthThresholds(5.0, 6.0, 0.0, 3.0));
    }

    @Test
    void replayAudioResolverSupportsCanonicalAndLegacyNestedKeys() {
        YamlConfiguration legacy = new YamlConfiguration();
        legacy.set("death-replay.terrain.audio.enabled", false);
        assertFalse(ConfigBounds.resolveReplayAudioEnabled(legacy));

        YamlConfiguration canonical = new YamlConfiguration();
        canonical.set("death-replay.terrain.audio.enabled", false);
        canonical.set("death-replay.audio.enabled", true);
        assertTrue(ConfigBounds.resolveReplayAudioEnabled(canonical));

        YamlConfiguration empty = new YamlConfiguration();
        assertTrue(ConfigBounds.resolveReplayAudioEnabled(empty));
    }

    @Test
    void deprecatedResourcePackHudLogsSingleWarningOnlyWhenEnabled() {
        Logger logger = Logger.getLogger("ConfigBoundsTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        List<LogRecord> records = new ArrayList<>();
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        });

        YamlConfiguration legacyFalse = new YamlConfiguration();
        legacyFalse.set(ConfigBounds.DEPRECATED_RESOURCE_PACK_HUD_KEY, false);
        assertFalse(ConfigBounds.warnIfDeprecatedResourcePackHudEnabled(legacyFalse, logger));
        assertTrue(records.isEmpty());

        YamlConfiguration legacyTrue = new YamlConfiguration();
        legacyTrue.set(ConfigBounds.DEPRECATED_RESOURCE_PACK_HUD_KEY, true);
        assertTrue(ConfigBounds.warnIfDeprecatedResourcePackHudEnabled(legacyTrue, logger));
        assertEquals(1, records.size());
        assertEquals(Level.WARNING, records.getFirst().getLevel());
        assertTrue(records.getFirst().getMessage().contains(ConfigBounds.DEPRECATED_RESOURCE_PACK_HUD_KEY));
        assertTrue(records.getFirst().getMessage().contains("standard Deathcam renderer"));
    }
}
