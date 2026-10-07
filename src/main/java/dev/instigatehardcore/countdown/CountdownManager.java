package dev.instigatehardcore.countdown;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.Objects;

public final class CountdownManager {

    private static final long TICKS_PER_SECOND = 20L;

    private static final Key COUNTDOWN_SOUND =
        Key.key("minecraft:block.note_block.hat");

    private static final Key COMPLETION_SOUND =
        Key.key("minecraft:entity.wither.death");

    private final JavaPlugin plugin;
    private final int durationSeconds;

    private BukkitTask countdownTask;
    private int remainingSeconds;

    public CountdownManager(
        JavaPlugin plugin,
        int durationSeconds
    ) {
        this.plugin = Objects.requireNonNull(plugin);

        if (durationSeconds < 1) {
            throw new IllegalArgumentException(
                "Countdown duration must be at least one second."
            );
        }

        this.durationSeconds = durationSeconds;
    }

    /**
     * Starts the run-ending countdown.
     *
     * Only one countdown may run at a time.
     *
     * @param attemptNumber current hardcore attempt number
     * @param onComplete action to execute when the countdown reaches zero
     * @return true if the countdown started
     */
    public synchronized boolean startCountdown(
        int attemptNumber,
        Runnable onComplete
    ) {
        Objects.requireNonNull(onComplete);

        if (countdownTask != null) {
            return false;
        }

        remainingSeconds = durationSeconds;

        countdownTask = plugin
            .getServer()
            .getScheduler()
            .runTaskTimer(
                plugin,
                () -> tick(attemptNumber, onComplete),
                0L,
                TICKS_PER_SECOND
            );

        plugin.getLogger().info(
            "[Instigate Cafe Hardcore] "
                + "Started "
                + durationSeconds
                + "-second reset countdown for attempt #"
                + attemptNumber
                + "."
        );

        return true;
    }

    private void tick(
        int attemptNumber,
        Runnable onComplete
    ) {
        if (remainingSeconds <= 0) {
            completeCountdown(onComplete);
            return;
        }

        showCountdown(
            attemptNumber,
            remainingSeconds
        );

        remainingSeconds--;
    }

    private void showCountdown(
        int attemptNumber,
        int seconds
    ) {
        NamedTextColor countdownColor;

        if (seconds <= 3) {
            countdownColor = NamedTextColor.RED;
        } else if (seconds <= 5) {
            countdownColor = NamedTextColor.GOLD;
        } else {
            countdownColor = NamedTextColor.YELLOW;
        }

        Component titleText = Component.text(
            "INSTIGATE CAFE HARDCORE",
            NamedTextColor.GOLD
        );

        Component subtitleText = Component.text()
            .append(
                Component.text(
                    "Attempt #" + attemptNumber,
                    NamedTextColor.GRAY
                )
            )
            .append(
                Component.text(
                    "  •  Resetting in ",
                    NamedTextColor.DARK_GRAY
                )
            )
            .append(
                Component.text(
                    seconds + "s",
                    countdownColor
                )
            )
            .build();

        Title title = Title.title(
            titleText,
            subtitleText,
            Title.Times.times(
                Duration.ZERO,
                Duration.ofMillis(1100),
                Duration.ZERO
            )
        );

        float pitch = seconds <= 3
            ? 1.4f
            : 1.1f;

        Sound sound = Sound.sound(
            COUNTDOWN_SOUND,
            Sound.Source.MASTER,
            0.7f,
            pitch
        );

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.showTitle(title);
            player.playSound(sound);
        }
    }

    private synchronized void completeCountdown(
        Runnable onComplete
    ) {
        if (countdownTask == null) {
            return;
        }

        countdownTask.cancel();
        countdownTask = null;
        remainingSeconds = 0;

        showCompletionTitle();

        plugin.getLogger().info(
            "[Instigate Cafe Hardcore] Reset countdown completed."
        );

        onComplete.run();
    }

    private void showCompletionTitle() {
        Component titleText = Component.text(
            "INSTIGATE CAFE HARDCORE",
            NamedTextColor.GOLD
        );

        Component subtitleText = Component.text(
            "Preparing a new world...",
            NamedTextColor.RED
        );

        Title title = Title.title(
            titleText,
            subtitleText,
            Title.Times.times(
                Duration.ofMillis(100),
                Duration.ofSeconds(3),
                Duration.ofMillis(500)
            )
        );

        Sound sound = Sound.sound(
            COMPLETION_SOUND,
            Sound.Source.MASTER,
            0.6f,
            1.0f
        );

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.showTitle(title);
            player.playSound(sound);
        }
    }

    public synchronized void cancel() {
        if (countdownTask == null) {
            return;
        }

        countdownTask.cancel();
        countdownTask = null;
        remainingSeconds = 0;

        plugin.getLogger().info(
            "[Instigate Cafe Hardcore] Countdown cancelled."
        );
    }

    public synchronized boolean isRunning() {
        return countdownTask != null;
    }

    public synchronized int getRemainingSeconds() {
        return remainingSeconds;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }
}