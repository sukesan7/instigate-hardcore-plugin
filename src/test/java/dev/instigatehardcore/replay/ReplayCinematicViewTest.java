package dev.instigatehardcore.replay;

import dev.instigatehardcore.core.ConfigBounds;
import dev.instigatehardcore.player.MockPlayerHelper;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReplayCinematicViewTest {

    @Test
    void freshAndLegacyResourcePackConfigsBothUseStandardAdventureHud() {
        YamlConfiguration fresh = new YamlConfiguration();
        fresh.set("death-replay.cinematic.hide-scoreboard", true);

        YamlConfiguration legacy = new YamlConfiguration();
        legacy.set("death-replay.cinematic.hide-scoreboard", true);
        legacy.set(ConfigBounds.DEPRECATED_RESOURCE_PACK_HUD_KEY, true);

        for (YamlConfiguration config : List.of(fresh, legacy)) {
            RecordedViewer viewer = new RecordedViewer(true, false, false);
            ReplayActorScene scene = createScene("Sukesan", 140);
            JavaPlugin plugin = pluginWithConfig(config);

            try (ReplayCinematicView view = new ReplayCinematicView(
                plugin, viewer.player, scene, false, viewer.scoreboardManager
            )) {
                assertEquals(1, viewer.shownBossBars.size(), "Standard purple boss bar must be shown");
                BossBar bossBar = viewer.shownBossBars.getFirst();
                assertEquals(BossBar.Color.PURPLE, bossBar.color());
                assertEquals("INSTIGATE CAFE  |  DEATHCAM: Sukesan",
                    ReplayCinematicHudTextTest.plain(bossBar.name()));
                ReplayCinematicHudTextTest.assertVanillaMinecraftComponent(bossBar.name());

                assertEquals(1, viewer.shownTitles.size(), "Standard Deathcam title must be shown");
                Title title = viewer.shownTitles.getFirst();
                assertEquals("DEATHCAM", ReplayCinematicHudTextTest.plain(title.title()));
                assertEquals("Sukesan", ReplayCinematicHudTextTest.plain(title.subtitle()));
                ReplayCinematicHudTextTest.assertVanillaMinecraftComponent(title.title());
                ReplayCinematicHudTextTest.assertVanillaMinecraftComponent(title.subtitle());

                assertSame(viewer.emptyScoreboard, viewer.currentScoreboard,
                    "Scoreboard should be hidden during cinematic replay");

                view.renderTick(0);
                assertEquals(1.0f, bossBar.progress(), 0.0001f);
                assertEquals("DEATHCAM: Sukesan   •   00:07",
                    ReplayCinematicHudTextTest.plain(viewer.actionBars.getLast()));
                ReplayCinematicHudTextTest.assertVanillaMinecraftComponent(viewer.actionBars.getLast());

                view.renderTick(70);
                assertEquals(0.5f, bossBar.progress(), 0.0001f);
                assertEquals("DEATHCAM: Sukesan   •   00:04",
                    ReplayCinematicHudTextTest.plain(viewer.actionBars.getLast()));
                ReplayCinematicHudTextTest.assertVanillaMinecraftComponent(viewer.actionBars.getLast());

                view.renderTick(140);
                assertEquals(0.0f, bossBar.progress(), 0.0001f);
                assertEquals("DEATHCAM: Sukesan   •   00:00",
                    ReplayCinematicHudTextTest.plain(viewer.actionBars.getLast()));
                ReplayCinematicHudTextTest.assertVanillaMinecraftComponent(viewer.actionBars.getLast());
            }

            assertEquals(1, viewer.hiddenBossBars.size(), "Boss bar must be hidden on close");
            assertEquals(1, viewer.clearTitleCalls, "Title must be cleared on close");
            assertEquals(Component.empty(), viewer.actionBars.getLast(),
                "Action bar must be cleared on close");
            assertSame(viewer.initialScoreboard, viewer.currentScoreboard,
                "Original scoreboard must be restored on close");
        }
    }

    @Test
    void closeRestoresActionBarAndScoreboardEvenWhenClearTitleThrows() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("death-replay.cinematic.hide-scoreboard", true);

        RecordedViewer viewer = new RecordedViewer(true, true, false);
        ReplayActorScene scene = createScene("Alex", 140);
        JavaPlugin plugin = pluginWithConfig(config);

        ReplayCinematicView view = new ReplayCinematicView(
            plugin, viewer.player, scene, false, viewer.scoreboardManager
        );
        view.renderTick(0);
        view.close();
        view.close(); // Idempotent

        assertEquals(1, viewer.hiddenBossBars.size());
        assertEquals(1, viewer.clearTitleCalls);
        assertEquals(Component.empty(), viewer.actionBars.getLast());
        assertSame(viewer.initialScoreboard, viewer.currentScoreboard);
    }

    @Test
    void failedTitleInitializationRollsBackBossBarAndScoreboard() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("death-replay.cinematic.hide-scoreboard", true);

        RecordedViewer viewer = new RecordedViewer(true, false, true);
        ReplayActorScene scene = createScene("Alex", 140);
        JavaPlugin plugin = pluginWithConfig(config);

        assertThrows(IllegalStateException.class, () ->
            new ReplayCinematicView(plugin, viewer.player, scene, false, viewer.scoreboardManager));

        assertEquals(1, viewer.hiddenBossBars.size(), "Boss bar must be hidden on failed init");
        assertEquals(1, viewer.clearTitleCalls, "Title cleanup must run on failed init");
        assertEquals(Component.empty(), viewer.actionBars.getLast(),
            "Action bar must be cleared on failed init");
        assertSame(viewer.initialScoreboard, viewer.currentScoreboard,
            "Scoreboard must be restored on failed init");
    }

    @Test
    void offlineViewerCloseSkipsHudPacketCallsSafely() {
        YamlConfiguration config = new YamlConfiguration();
        RecordedViewer viewer = new RecordedViewer(true, false, false);
        ReplayActorScene scene = createScene("Alex", 140);
        JavaPlugin plugin = pluginWithConfig(config);

        ReplayCinematicView view = new ReplayCinematicView(
            plugin, viewer.player, scene, false, viewer.scoreboardManager
        );
        viewer.online = false;
        view.close();

        assertTrue(viewer.hiddenBossBars.isEmpty());
        assertEquals(0, viewer.clearTitleCalls);
    }

    private static ReplayActorScene createScene(String victimName, int durationTicks) {
        UUID victimId = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();
        ReplayActorSnapshot start = new ReplayActorSnapshot(
            victimId, "PLAYER", victimName,
            0.0, 64.0, 0.0, 0.0f, 0.0f,
            false, false, false, 20.0, ReplayEquipment.empty(),
            false, true, "GRASS_BLOCK", ReplayCreeperState.NONE
        );
        ReplayActorSnapshot end = new ReplayActorSnapshot(
            victimId, "PLAYER", victimName,
            4.0, 64.0, 2.0, 45.0f, 5.0f,
            false, false, false, 0.0, ReplayEquipment.empty(),
            false, true, "GRASS_BLOCK", ReplayCreeperState.NONE
        );
        ReplayClip clip = new ReplayClip(
            1, victimId, worldId,
            List.of(
                new ReplayFrame(10L, 1, worldId, victimId, List.of(start)),
                new ReplayFrame(10L + durationTicks, 1, worldId, victimId, List.of(end))
            )
        );
        ReplayDeathMoment death = new ReplayDeathMoment(
            1, victimId, worldId, 10L + durationTicks, 1000L,
            4.0, 64.0, 2.0, 45.0f, 5.0f,
            victimName + " was blown up by Creeper", "Creeper"
        );
        return new ReplayActorScene(new FrozenDeathReplay(clip, death, List.of()), durationTicks);
    }

    private static JavaPlugin pluginWithConfig(FileConfiguration config) {
        JavaPlugin plugin = MockPlayerHelper.createMockPlugin();
        try {
            Field newConfig = JavaPlugin.class.getDeclaredField("newConfig");
            newConfig.setAccessible(true);
            newConfig.set(plugin, config);
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException("Unable to inject FileConfiguration into mock plugin", exception);
        }
        return plugin;
    }

    private static final class RecordedViewer {
        final Scoreboard initialScoreboard;
        final Scoreboard emptyScoreboard;
        final ScoreboardManager scoreboardManager;
        final Player player;

        final List<BossBar> shownBossBars = new ArrayList<>();
        final List<BossBar> hiddenBossBars = new ArrayList<>();
        final List<Title> shownTitles = new ArrayList<>();
        final List<Component> actionBars = new ArrayList<>();
        int clearTitleCalls;
        Scoreboard currentScoreboard;
        boolean online;

        RecordedViewer(boolean online, boolean throwOnClearTitle, boolean throwOnShowTitle) {
            this.online = online;
            this.initialScoreboard = mockScoreboard("initial");
            this.emptyScoreboard = mockScoreboard("empty");
            this.currentScoreboard = initialScoreboard;
            this.scoreboardManager = (ScoreboardManager) Proxy.newProxyInstance(
                ScoreboardManager.class.getClassLoader(),
                new Class<?>[]{ScoreboardManager.class},
                (proxy, method, args) -> {
                    if ("getNewScoreboard".equals(method.getName())) {
                        return emptyScoreboard;
                    }
                    if ("getMainScoreboard".equals(method.getName())) {
                        return initialScoreboard;
                    }
                    return null;
                }
            );
            UUID uuid = UUID.randomUUID();
            this.player = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getName" -> "Spectator";
                    case "isOnline" -> this.online;
                    case "getScoreboard" -> this.currentScoreboard;
                    case "setScoreboard" -> {
                        this.currentScoreboard = (Scoreboard) args[0];
                        yield null;
                    }
                    case "showBossBar" -> {
                        this.shownBossBars.add((BossBar) args[0]);
                        yield null;
                    }
                    case "hideBossBar" -> {
                        this.hiddenBossBars.add((BossBar) args[0]);
                        yield null;
                    }
                    case "showTitle" -> {
                        if (throwOnShowTitle) {
                            throw new IllegalStateException("Simulated title failure");
                        }
                        this.shownTitles.add((Title) args[0]);
                        yield null;
                    }
                    case "clearTitle" -> {
                        this.clearTitleCalls++;
                        if (throwOnClearTitle) {
                            throw new IllegalStateException("Simulated clearTitle failure");
                        }
                        yield null;
                    }
                    case "sendActionBar" -> {
                        this.actionBars.add((Component) args[0]);
                        yield null;
                    }
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> uuid.hashCode();
                    default -> method.getReturnType().equals(boolean.class) ? false
                        : method.getReturnType().isPrimitive() ? 0 : null;
                }
            );
        }

        private static Scoreboard mockScoreboard(String tag) {
            return (Scoreboard) Proxy.newProxyInstance(
                Scoreboard.class.getClassLoader(),
                new Class<?>[]{Scoreboard.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> tag.hashCode();
                    case "toString" -> "MockScoreboard[" + tag + "]";
                    default -> null;
                }
            );
        }
    }
}
