package dev.instigatehardcore.replay;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;

import java.time.Duration;
import java.util.Locale;

/**
 * Deathcam v0.4: compact, center-anchored title/actionbar HUD.
 *
 * Sprite tiles are each <= 256x256 (Minecraft font atlas limit). The 307px
 * complete lower panel fits the standard 320px minimum logical viewport and
 * therefore scales with the client's GUI scale instead of fixing physical px.
 * This is not a true edge-to-edge overlay; vanilla clients do not report
 * display resolution or GUI scale to Paper plugins.
 */
public final class ResourcePackReplayHud {
    private static final Key FONT = Key.key("instigatehardcore:deathcam");
    private static final Key NAME_FONT = Key.key("instigatehardcore:deathcam_names");

    private static final int TOTAL_ADVANCE = 307;
    private static final int LEFT_TEXT_X = 11;
    private static final int RIGHT_TEXT_X = 182;
    private static final int NAME_ADVANCE = 7;
    private static final int MAX_NAME_LENGTH = 16;

    private static final TextColor LILAC = TextColor.color(0xD8B5FF);
    private static final TextColor AZURE = TextColor.color(0x69D9FD);

    private ResourcePackReplayHud() { }

    public static Title title(int durationTicks) {
        return Title.title(
            Component.text("\uE100", TextColor.color(0xFFFFFF)).font(FONT),
            Component.empty(),
            Title.Times.times(Duration.ZERO,
                Duration.ofMillis(Math.max(1, durationTicks) * 50L + 250L),
                Duration.ZERO)
        );
    }

    // Invisible font codepoints with powers-of-two positive/negative advances.
    // Never use literal spaces here: their metrics vary with the current font.
    private static Component move(int pixels) {
        int magnitude = Math.abs(pixels);
        if (magnitude > 2047) throw new IllegalArgumentException("HUD shift exceeds font capacity");
        StringBuilder chars = new StringBuilder();
        for (int bit = 10; bit >= 0; bit--) {
            int n = 1 << bit;
            if ((magnitude & n) != 0) {
                chars.append((char) ((pixels < 0 ? 0xE320 : 0xE300) + bit));
            }
        }
        return Component.text(chars.toString()).font(FONT);
    }

    /** Only names/causes from recorded death metadata; no fabricated weapon names. */
    private static String normalized(String raw) {
        String clean = ReplayCinematicHudText.cleanName(raw);
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < clean.length() && safe.length() < MAX_NAME_LENGTH; i++) {
            char c = clean.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9') || " _-.!?/:#()[]+,".indexOf(c) >= 0) {
                safe.append(c);
            }
        }
        return safe.isEmpty() ? "Unknown" : safe.toString();
    }

    private static boolean verifiedPlayerKiller(ReplayActorScene scene, String cause) {
        // DeathListener stores the last damager (including projectile shooter)
        // in deathCause. Confirm it's a distinct player actor in the clip.
        return scene.at(0).values().stream().anyMatch(actor ->
            !actor.id().equals(scene.death().victimId())
                && actor.name().equalsIgnoreCase(cause)
                && actor.entityType().toLowerCase(Locale.ROOT).endsWith("player"))
            || scene.at(scene.durationTicks()).values().stream().anyMatch(actor ->
                !actor.id().equals(scene.death().victimId())
                    && actor.name().equalsIgnoreCase(cause)
                    && actor.entityType().toLowerCase(Locale.ROOT).endsWith("player"));
    }

    public static Component actionBar(String victimName, ReplayActorScene scene) {
        String victim = normalized(victimName);
        String cause = normalized(scene.death().deathCause());
        boolean playerKiller = verifiedPlayerKiller(scene, scene.death().deathCause());
        String right = cause;

        // Total advance: 133 + 40 + 134 = 307. Both text overlays reset to
        // x=0 before adding TOTAL_ADVANCE back. The actionbar center stays
        // fixed regardless of name lengths, chat activity, or client window.
        TextComponent.Builder ui = Component.text();
        ui.append(Component.text("\uE200\uE201" + (playerKiller ? "\uE202" : "\uE203"))
            .font(FONT).color(TextColor.color(0xFFFFFF)));
        ui.append(move(-TOTAL_ADVANCE));

        ui.append(move(LEFT_TEXT_X));
        ui.append(Component.text(victim, LILAC).font(NAME_FONT));
        ui.append(move(-LEFT_TEXT_X - NAME_ADVANCE * victim.length()));

        ui.append(move(RIGHT_TEXT_X));
        ui.append(Component.text(right, AZURE).font(NAME_FONT));
        ui.append(move(-RIGHT_TEXT_X - NAME_ADVANCE * right.length()));

        ui.append(move(TOTAL_ADVANCE));
        return ui.build();
    }
}
