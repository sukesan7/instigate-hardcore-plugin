package dev.instigatehardcore.countdown;

import dev.instigatehardcore.ui.InstigateTheme;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.Objects;

public final class CountdownManager {

    private final JavaPlugin plugin;

    private final int durationSeconds;

    private BukkitTask countdownTask;

    private int remainingSeconds;

    public CountdownManager(
        JavaPlugin plugin,
        int durationSeconds
    ) {
        this.plugin =
            Objects.requireNonNull(
                plugin
            );

        if (durationSeconds < 1) {
            throw new IllegalArgumentException(
                "Countdown duration must be at least one second."
            );
        }

        this.durationSeconds =
            durationSeconds;
    }

    /*
     * ------------------------------------------------------------
     * COUNTDOWN
     * ------------------------------------------------------------
     */

    public synchronized void startCountdown(
        int attemptNumber,
        Runnable onComplete
    ) {
        startCountdown(attemptNumber, durationSeconds, onComplete);
    }

    /**
     * Phase 9E: allow the ordinary countdown to run for the last
     * three seconds after a seven-second death replay.
     * The original two-argument overload still uses the configured
     * duration, including for administrative resets.
     */
    public synchronized void startCountdown(
        int attemptNumber,
        int seconds,
        Runnable onComplete
    ) {
        Objects.requireNonNull(onComplete);
        if (seconds < 1 || seconds > 600) {
            throw new IllegalArgumentException("Countdown must be 1..600 seconds.");
        }

        cancel();

        remainingSeconds = seconds;

        /*
         * Update immediately, then once every second.
         */
        countdownTask =
            plugin.getServer()
                .getScheduler()
                .runTaskTimer(
                    plugin,
                    () -> tick(
                        attemptNumber,
                        onComplete
                    ),
                    0L,
                    20L
                );
    }

    private synchronized void tick(
        int attemptNumber,
        Runnable onComplete
    ) {
        if (remainingSeconds <= 0) {
            finish(
                onComplete
            );

            return;
        }

        showCountdown(
            attemptNumber,
            remainingSeconds
        );

        playCountdownSound(
            remainingSeconds
        );

        remainingSeconds--;
    }

    private void showCountdown(
        int attemptNumber,
        int seconds
    ) {
        /*
         * Minecraft does not expose arbitrary title scaling.
         *
         * Using the subtitle layer by itself gives us a smaller,
         * cleaner centered countdown without requiring a client
         * mod or resource pack.
         */
        Component countdownLine =
            Component.text()
                .append(
                    Component.text(
                        "Attempt #"
                            + attemptNumber,
                        InstigateTheme.PURPLE
                    )
                )
                .append(
                    InstigateTheme.muted(
                        "  ·  "
                    )
                )
                .append(
                    InstigateTheme.secondary(
                        "Resetting in "
                    )
                )
                .append(
                    Component.text(
                        seconds
                            + "s",
                        InstigateTheme.AZURE
                    )
                )
                .build();

        Title title =
            Title.title(
                Component.empty(),
                countdownLine,
                Title.Times.times(
                    Duration.ZERO,
                    Duration.ofMillis(
                        1100
                    ),
                    Duration.ZERO
                )
            );

        for (
            Player player :
            plugin.getServer()
                .getOnlinePlayers()
        ) {
            player.showTitle(
                title
            );
        }
    }

    /*
     * ------------------------------------------------------------
     * SOUND
     * ------------------------------------------------------------
     */

    private void playCountdownSound(
        int seconds
    ) {
        /*
         * Keep the countdown sound subtle.
         *
         * The final three seconds rise slightly in pitch.
         */
        float pitch;

        if (seconds <= 1) {
            pitch =
                1.6f;
        } else if (seconds == 2) {
            pitch =
                1.4f;
        } else if (seconds == 3) {
            pitch =
                1.2f;
        } else {
            pitch =
                1.0f;
        }

        for (
            Player player :
            plugin.getServer()
                .getOnlinePlayers()
        ) {
            player.playSound(
                player.getLocation(),
                Sound.BLOCK_NOTE_BLOCK_HAT,
                0.35f,
                pitch
            );
        }
    }

    /*
     * ------------------------------------------------------------
     * COMPLETION
     * ------------------------------------------------------------
     */

    private synchronized void finish(
        Runnable onComplete
    ) {
        if (countdownTask != null) {
            countdownTask.cancel();

            countdownTask =
                null;
        }

        remainingSeconds =
            0;

        /*
         * Remove the countdown before WorldRotationManager sends
         * the fresh-attempt title.
         */
        for (
            Player player :
            plugin.getServer()
                .getOnlinePlayers()
        ) {
            player.clearTitle();
        }

        onComplete.run();
    }

    /*
     * ------------------------------------------------------------
     * CANCELLATION
     * ------------------------------------------------------------
     */

    public synchronized void cancel() {
        if (countdownTask != null) {
            countdownTask.cancel();

            countdownTask =
                null;
        }

        remainingSeconds =
            0;

        for (
            Player player :
            plugin.getServer()
                .getOnlinePlayers()
        ) {
            player.clearTitle();
        }
    }

    /*
     * ------------------------------------------------------------
     * STATE
     * ------------------------------------------------------------
     */

    public synchronized boolean isRunning() {
        return countdownTask != null;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public synchronized int getRemainingSeconds() {
        return remainingSeconds;
    }
}