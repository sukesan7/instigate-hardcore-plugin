package dev.instigatehardcore.ui;

import java.util.Locale;

/** Formats a health-point quantity as familiar half-heart increments. */
public final class HeartDisplayFormatter {
    private HeartDisplayFormatter() {}

    public static String hearts(double healthPoints) {
        if (!Double.isFinite(healthPoints)) return "?";
        // Show one decimal without rounding a critically injured player upward
        // across a warning threshold (e.g., 1.95 hearts must not look like 2.0).
        double rounded = Math.max(0.0, Math.floor(healthPoints * 5.0 + 1e-9) / 10.0);
        if (rounded == Math.floor(rounded)) {
            return String.format(Locale.ROOT, "%.0f", rounded);
        }
        return String.format(Locale.ROOT, "%.1f", rounded);
    }
}
