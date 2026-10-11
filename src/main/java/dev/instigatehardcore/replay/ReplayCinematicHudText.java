package dev.instigatehardcore.replay;

/** Pure timer/name formatting shared by the action bar and boss bar. */
public final class ReplayCinematicHudText {
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
        return String.format(java.util.Locale.ROOT, "00:%02d",
            secondsLeft(elapsedTick, durationTicks));
    }

    public static float fractionRemaining(int elapsedTick, int durationTicks) {
        if (durationTicks < 1) throw new IllegalArgumentException("Duration must be positive.");
        return Math.max(0f, Math.min(1f,
            (float) (durationTicks - Math.max(0, elapsedTick)) / durationTicks));
    }
}
