package dev.instigatehardcore.replay;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReplayCinematicHudTextTest {
    @Test
    void sevenSecondClockCountsDownWithoutPrematureZero() {
        assertEquals("00:07", ReplayCinematicHudText.clock(0, 140));
        assertEquals("00:07", ReplayCinematicHudText.clock(1, 140));
        assertEquals("00:06", ReplayCinematicHudText.clock(20, 140));
        assertEquals("00:01", ReplayCinematicHudText.clock(139, 140));
        assertEquals("00:00", ReplayCinematicHudText.clock(140, 140));
    }

    @Test
    void neverDisplaysNegativeSeconds() {
        assertEquals(0, ReplayCinematicHudText.secondsLeft(200, 140));
        assertEquals(7, ReplayCinematicHudText.secondsLeft(-5, 140));
        assertThrows(IllegalArgumentException.class, () ->
            ReplayCinematicHudText.clock(0, 0));
    }

    @Test
    void progressStartsFullAndEndsEmpty() {
        assertEquals(1f, ReplayCinematicHudText.fractionRemaining(0, 140));
        assertEquals(0.5f, ReplayCinematicHudText.fractionRemaining(70, 140));
        assertEquals(0f, ReplayCinematicHudText.fractionRemaining(140, 140));
        assertEquals(0f, ReplayCinematicHudText.fractionRemaining(160, 140));
    }

    @Test
    void sanitizesNamesUsedInHud() {
        assertEquals("Unknown Player", ReplayCinematicHudText.cleanName("  "));
        assertEquals("scarbz", ReplayCinematicHudText.cleanName("scarbz"));
        assertEquals("AB", ReplayCinematicHudText.cleanName("A§B"));
        assertEquals(32, ReplayCinematicHudText.cleanName("x".repeat(80)).length());
    }

    @Test
    void standardHudComponentsUseDefaultFontAndNoPrivateUseGlyphs() {
        BossBar bossBar = ReplayCinematicHudText.bossBar("Sukesan");
        assertEquals(BossBar.Color.PURPLE, bossBar.color());
        assertEquals(BossBar.Overlay.PROGRESS, bossBar.overlay());
        assertEquals(1.0f, bossBar.progress());
        assertEquals("INSTIGATE CAFE  |  DEATHCAM: Sukesan", plain(bossBar.name()));
        assertVanillaMinecraftComponent(bossBar.name());

        Title title = ReplayCinematicHudText.title("Sukesan");
        assertEquals("DEATHCAM", plain(title.title()));
        assertEquals("Sukesan", plain(title.subtitle()));
        assertVanillaMinecraftComponent(title.title());
        assertVanillaMinecraftComponent(title.subtitle());

        Component actionStart = ReplayCinematicHudText.actionBar("Sukesan", 0, 140);
        assertEquals("DEATHCAM: Sukesan   •   00:07", plain(actionStart));
        assertVanillaMinecraftComponent(actionStart);

        Component actionEnd = ReplayCinematicHudText.actionBar("Sukesan", 140, 140);
        assertEquals("DEATHCAM: Sukesan   •   00:00", plain(actionEnd));
        assertVanillaMinecraftComponent(actionEnd);
    }

    static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    static void assertVanillaMinecraftComponent(Component component) {
        assertNull(
            component.font(),
            "Standard Deathcam HUD must never reference a custom font: " + component.font()
        );
        String text = plain(component);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            assertFalse(
                c >= '\uE000' && c <= '\uF8FF',
                String.format("Found private-use glyph U+%04X in component text %s", (int) c, text)
            );
        }
        for (Component child : component.children()) {
            assertVanillaMinecraftComponent(child);
        }
    }
}
