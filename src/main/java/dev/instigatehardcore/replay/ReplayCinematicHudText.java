package dev.instigatehardcore.replay;

import dev.instigatehardcore.ui.InstigateTheme;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;

import java.time.Duration;
import java.util.Locale;

/**
 * Pure formatting and standard Adventure HUD component builders for Deathcam.
 *
 * Uses only standard Minecraft fonts and Adventure components so every vanilla
 * and compatible client renders Deathcam cleanly without external resource packs.
 */
public final class ReplayCinematicHudText {
    private static final Duration TITLE_STAY = Duration.ofMillis(650);
    private static final Duration TITLE_FADE_OUT = Duration.ofMillis(250);

    private ReplayCinematicHudText() { }

    public static String cleanName(String input) {
        if (input == null || input.isBlank()) return "Unknown Player";
        String clean = input.replaceAll("[\\p{Cntrl}§]", "").strip();
        if (clean.isBlank()) return "Unknown Player";
        return clean.length() <= 32 ? clean : clean.substring(0, 32);
    }

    /** Always show a full remaining second until its last tick expires. */
    public static int secondsLeft(int elapsedTick, int durationTicks) {
        if (durationTicks < 1) throw new IllegalArgumentException("Duration must be positive.");
        int remaining = Math.max(0, durationTicks - Math.max(0, elapsedTick));
        return (remaining + 19) / 20;
    }

    public static String clock(int elapsedTick, int durationTicks) {
        return String.format(Locale.ROOT, "00:%02d",
            secondsLeft(elapsedTick, durationTicks));
    }

    public static float fractionRemaining(int elapsedTick, int durationTicks) {
        if (durationTicks < 1) throw new IllegalArgumentException("Duration must be positive.");
        return Math.max(0f, Math.min(1f,
            (float) (durationTicks - Math.max(0, elapsedTick)) / durationTicks));
    }

    public static BossBar bossBar(String victimName) {
        return BossBar.bossBar(
            Component.text("INSTIGATE CAFE  |  DEATHCAM: " + cleanName(victimName), InstigateTheme.TEXT),
            1.0f,
            BossBar.Color.PURPLE,
            BossBar.Overlay.PROGRESS
        );
    }

    public static Title title(String victimName) {
        return Title.title(
            Component.text("DEATHCAM", InstigateTheme.PURPLE)
                .decorate(TextDecoration.BOLD),
            Component.text(cleanName(victimName), InstigateTheme.TEXT),
            Title.Times.times(Duration.ZERO, TITLE_STAY, TITLE_FADE_OUT)
        );
    }

    public static Component actionBar(String victimName, int playbackTick, int durationTicks) {
        return Component.text()
            .append(Component.text("DEATHCAM: ", InstigateTheme.PURPLE)
                .decorate(TextDecoration.BOLD))
            .append(Component.text(cleanName(victimName), InstigateTheme.TEXT))
            .append(Component.text("   •   ", InstigateTheme.MUTED))
            .append(Component.text(
                clock(playbackTick, durationTicks),
                InstigateTheme.AZURE
            ))
            .build();
    }
}
