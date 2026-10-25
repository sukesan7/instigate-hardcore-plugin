package dev.instigatehardcore.listener;

import dev.instigatehardcore.core.RunManager;
import dev.instigatehardcore.participation.AttemptParticipantManager;
import dev.instigatehardcore.player.MockPlayerHelper;
import dev.instigatehardcore.player.PlayerResetManager;
import dev.instigatehardcore.stats.StatsManager;
import dev.instigatehardcore.telemetry.PlayerTelemetryManager;
import dev.instigatehardcore.world.WorldSet;
import dev.instigatehardcore.world.WorldSetManager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class PlayerJoinListenerInsomniaTest {

    @TempDir
    Path tempDir;

    private JavaPlugin plugin;
    private StatsManager statsManager;
    private RunManager runManager;
    private PlayerResetManager playerResetManager;
    private WorldSetManager worldSetManager;
    private AttemptParticipantManager participantManager;
    private PlayerTelemetryManager telemetryManager;

    private World activeOverworld;
    private World activeNether;
    private World activeEnd;
    private World lobbyWorld;
    private WorldSet activeWorldSet;

    @BeforeEach
    void setUp() throws Exception {
        plugin = MockPlayerHelper.createMockPlugin();

        Path statsFile = tempDir.resolve("stats.properties");
        statsManager = new StatsManager(statsFile);
        statsManager.load();

        Path partFile = tempDir.resolve("attempt-participants.properties");
        participantManager = new AttemptParticipantManager(partFile);
        participantManager.load();

        Path telemetryFile = tempDir.resolve("player-telemetry.yml");
        telemetryManager = new PlayerTelemetryManager(telemetryFile);

        runManager = new RunManager();
        runManager.startRun(); // ACTIVE

        playerResetManager = new PlayerResetManager(plugin);

        // Setup mock worlds
        activeOverworld = MockPlayerHelper.createMockWorld("world_attempt_1");
        activeNether = MockPlayerHelper.createMockWorld("world_attempt_1_nether");
        activeEnd = MockPlayerHelper.createMockWorld("world_attempt_1_the_end");
        lobbyWorld = MockPlayerHelper.createMockWorld("world_lobby");

        activeWorldSet = new WorldSet(1, 12345L, activeOverworld, activeNether, activeEnd);

        // Allocate WorldSetManager without Paper server lifecycle
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        worldSetManager = (WorldSetManager) unsafe.allocateInstance(WorldSetManager.class);

        Field activeSetField = WorldSetManager.class.getDeclaredField("activeWorldSet");
        activeSetField.setAccessible(true);
        activeSetField.set(worldSetManager, activeWorldSet);

        Field lobbyWorldField = WorldSetManager.class.getDeclaredField("lobbyWorld");
        lobbyWorldField.setAccessible(true);
        lobbyWorldField.set(worldSetManager, lobbyWorld);
    }

    private PlayerJoinListener createListener(boolean allowLateJoiners) {
        return new PlayerJoinListener(
            plugin,
            statsManager,
            null, // HardcoreScoreboardManager (disabled/null)
            runManager,
            playerResetManager,
            worldSetManager,
            participantManager,
            telemetryManager,
            allowLateJoiners
        );
    }

    private void invokePlacePlayer(PlayerJoinListener listener, Player player) throws Exception {
        Method method = PlayerJoinListener.class.getDeclaredMethod("placePlayer", Player.class);
        method.setAccessible(true);
        method.invoke(listener, player);
    }

    private void invokeVerifyActivePlacement(
        PlayerJoinListener listener,
        Player player,
        int expectedAttempt,
        boolean needsFreshReset,
        int verificationAttempt
    ) throws Exception {
        Method method = PlayerJoinListener.class.getDeclaredMethod(
            "verifyActivePlacement",
            Player.class,
            int.class,
            boolean.class,
            int.class
        );
        method.setAccessible(true);
        method.invoke(listener, player, expectedAttempt, needsFreshReset, verificationAttempt);
    }

    @Test
    @DisplayName("Newly admitted participant receives fresh reset and zeroed insomnia")
    void newlyAdmittedParticipantGetsZeroedInsomnia() throws Exception {
        PlayerJoinListener listener = createListener(true);

        UUID uuid = UUID.randomUUID();
        Player player = MockPlayerHelper.createMockPlayer(uuid, "LateJoiner");
        player.setStatistic(Statistic.TIME_SINCE_REST, 85000);
        // Player currently in lobby
        player.teleport(new Location(lobbyWorld, 0, 64, 0));

        invokePlacePlayer(listener, player);

        assertEquals(0, player.getStatistic(Statistic.TIME_SINCE_REST),
            "Fresh participant must have TIME_SINCE_REST zeroed");
        assertTrue(participantManager.hasParticipant(1, uuid),
            "Player must be recorded as attempt participant");
        assertEquals(GameMode.SURVIVAL, player.getGameMode());
    }

    @Test
    @DisplayName("Newly admitted participant already in active world receives fresh reset and zeroed insomnia")
    void newlyAdmittedPlayerAlreadyInActiveWorldGetsZeroedInsomnia() throws Exception {
        PlayerJoinListener listener = createListener(true);

        UUID uuid = UUID.randomUUID();
        Player player = MockPlayerHelper.createMockPlayer(uuid, "SpawnedInActiveWorld");
        player.setStatistic(Statistic.TIME_SINCE_REST, 95000);
        // Player's location is already inside active Overworld
        player.teleport(new Location(activeOverworld, 50, 64, 50));

        assertFalse(participantManager.hasParticipant(1, uuid),
            "Precondition: player has not participated yet");

        invokePlacePlayer(listener, player);

        assertEquals(0, player.getStatistic(Statistic.TIME_SINCE_REST),
            "Newly admitted participant already in active world must receive fresh insomnia reset");
        assertTrue(participantManager.hasParticipant(1, uuid),
            "Player must now be marked as attempt participant");
    }

    @Test
    @DisplayName("Existing participant reconnecting to same attempt retains accumulated insomnia")
    void reconnectingToSameAttemptPreservesInsomnia() throws Exception {
        PlayerJoinListener listener = createListener(true);

        UUID uuid = UUID.randomUUID();
        Player player = MockPlayerHelper.createMockPlayer(uuid, "Veteran");
        player.teleport(new Location(activeOverworld, 100, 64, 100));

        // Record as prior participant in Attempt 1
        participantManager.recordParticipant(1, uuid, "Veteran");

        // Player has accumulated 45,000 ticks of insomnia
        player.setStatistic(Statistic.TIME_SINCE_REST, 45000);

        invokePlacePlayer(listener, player);

        assertEquals(45000, player.getStatistic(Statistic.TIME_SINCE_REST),
            "Reconnecting to same attempt must preserve accumulated insomnia ticks");

        // Verification retries must also preserve the statistic
        invokeVerifyActivePlacement(listener, player, 1, false, 1);
        assertEquals(45000, player.getStatistic(Statistic.TIME_SINCE_REST),
            "Verification retry must not wipe accumulated insomnia");

        invokeVerifyActivePlacement(listener, player, 1, false, 2);
        assertEquals(45000, player.getStatistic(Statistic.TIME_SINCE_REST),
            "Second verification pass must not wipe accumulated insomnia");
    }

    @Test
    @DisplayName("Player missing an attempt and joining next attempt receives fresh reset")
    void reconnectingAfterMissingAttemptGetsFreshReset() throws Exception {
        PlayerJoinListener listener = createListener(true);

        UUID uuid = UUID.randomUUID();
        Player player = MockPlayerHelper.createMockPlayer(uuid, "ReturningPlayer");

        // Player was in Attempt 1 (prior attempt)
        participantManager.recordParticipant(1, uuid, "ReturningPlayer");

        // Advance campaign to Attempt 2
        statsManager.advanceAttempt();
        WorldSet attempt2WorldSet = new WorldSet(2, 54321L, activeOverworld, activeNether, activeEnd);
        Field activeSetField = WorldSetManager.class.getDeclaredField("activeWorldSet");
        activeSetField.setAccessible(true);
        activeSetField.set(worldSetManager, attempt2WorldSet);

        player.setStatistic(Statistic.TIME_SINCE_REST, 80000);
        player.teleport(new Location(activeOverworld, 20, 64, 20));

        assertFalse(participantManager.hasParticipant(2, uuid),
            "Player has not participated in Attempt 2");

        invokePlacePlayer(listener, player);

        assertEquals(0, player.getStatistic(Statistic.TIME_SINCE_REST),
            "Joining Attempt 2 for the first time must zero insomnia");
        assertTrue(participantManager.hasParticipant(2, uuid),
            "Player must now be registered in Attempt 2");
    }

    @Test
    @DisplayName("Blocked late joiners do not receive attempt placement or statistic reset")
    void blockedLateJoinerDoesNotReceiveReset() throws Exception {
        // Disallow late joiners
        PlayerJoinListener listener = createListener(false);

        // Seed attempt with an existing participant so it is considered already started
        UUID existingId = UUID.randomUUID();
        participantManager.recordParticipant(1, existingId, "Host");

        UUID lateJoinerId = UUID.randomUUID();
        Player lateJoiner = MockPlayerHelper.createMockPlayer(lateJoinerId, "BlockedPlayer");
        lateJoiner.setStatistic(Statistic.TIME_SINCE_REST, 85000);
        lateJoiner.teleport(new Location(activeOverworld, 10, 64, 10));

        invokePlacePlayer(listener, lateJoiner);

        assertFalse(participantManager.hasParticipant(1, lateJoinerId),
            "Blocked late joiner must not be recorded as participant");
        assertEquals(85000, lateJoiner.getStatistic(Statistic.TIME_SINCE_REST),
            "Blocked late joiner's statistics must not be modified");
    }

    @Test
    @DisplayName("Placement when run is not active moves player to safety lobby without attempt reset")
    void inactiveRunDoesNotGrantAttemptReset() throws Exception {
        PlayerJoinListener listener = createListener(true);
        runManager.beginEnding(); // State is now ENDING

        UUID uuid = UUID.randomUUID();
        Player player = MockPlayerHelper.createMockPlayer(uuid, "WaitingPlayer");
        player.setStatistic(Statistic.TIME_SINCE_REST, 60000);

        invokePlacePlayer(listener, player);

        assertFalse(participantManager.hasParticipant(1, uuid),
            "Must not become participant while run is ending");
        assertEquals(60000, player.getStatistic(Statistic.TIME_SINCE_REST),
            "Insomnia timer must not be cleared during non-active placement");
    }
}
