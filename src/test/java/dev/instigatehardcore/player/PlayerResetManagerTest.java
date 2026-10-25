package dev.instigatehardcore.player;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class PlayerResetManagerTest {

    private JavaPlugin plugin;
    private PlayerResetManager resetManager;
    private World mockWorld;
    private Location spawnLocation;

    @BeforeEach
    void setUp() {
        plugin = MockPlayerHelper.createMockPlugin();
        resetManager = new PlayerResetManager(plugin);
        mockWorld = MockPlayerHelper.createMockWorld("world_attempt_1");
        spawnLocation = new Location(mockWorld, 100, 64, 100);
    }

    @Test
    @DisplayName("resetInsomniaState resets TIME_SINCE_REST to zero")
    void resetInsomniaStateZerosInsomnia() {
        Player player = MockPlayerHelper.createMockPlayer(UUID.randomUUID(), "Sukesan");
        player.setStatistic(Statistic.TIME_SINCE_REST, 75000);

        assertEquals(75000, player.getStatistic(Statistic.TIME_SINCE_REST));

        resetManager.resetInsomniaState(player);

        assertEquals(0, player.getStatistic(Statistic.TIME_SINCE_REST));
    }

    @Test
    @DisplayName("resetInsomniaState targets only TIME_SINCE_REST and preserves other statistics")
    void resetInsomniaStatePreservesOtherStatistics() {
        Player player = MockPlayerHelper.createMockPlayer(UUID.randomUUID(), "Sukesan");
        player.setStatistic(Statistic.TIME_SINCE_REST, 80000);
        player.setStatistic(Statistic.TIME_SINCE_DEATH, 5000);
        player.setStatistic(Statistic.PLAY_ONE_MINUTE, 120000);

        resetManager.resetInsomniaState(player);

        assertEquals(0, player.getStatistic(Statistic.TIME_SINCE_REST),
            "TIME_SINCE_REST must be zeroed");
        assertEquals(5000, player.getStatistic(Statistic.TIME_SINCE_DEATH),
            "TIME_SINCE_DEATH must not be altered by insomnia reset");
        assertEquals(120000, player.getStatistic(Statistic.PLAY_ONE_MINUTE),
            "PLAY_ONE_MINUTE must not be altered by insomnia reset");
    }

    @Test
    @DisplayName("prepareForNewAttempt zeroes insomnia value above 72,000 ticks across attempts")
    void prepareForNewAttemptResetsInsomniaAboveThreshold() {
        Player player = MockPlayerHelper.createMockPlayer(UUID.randomUUID(), "Alice");
        // Simulate player being awake for 4 in-game days in Attempt N (> 72,000 ticks)
        player.setStatistic(Statistic.TIME_SINCE_REST, 96000);

        resetManager.prepareForNewAttempt(player, spawnLocation);

        assertEquals(0, player.getStatistic(Statistic.TIME_SINCE_REST),
            "Insomnia counter must not carry over from Attempt N into Attempt N+1");
        assertEquals(GameMode.SURVIVAL, player.getGameMode(),
            "Player must be in SURVIVAL mode after new attempt preparation");
    }

    @Test
    @DisplayName("prepareForNewAttempt zeroes insomnia for both victim and surviving participants")
    void prepareForNewAttemptResetsMultipleParticipants() {
        Player victim = MockPlayerHelper.createMockPlayer(UUID.randomUUID(), "VictimPlayer");
        Player survivor1 = MockPlayerHelper.createMockPlayer(UUID.randomUUID(), "SurvivorOne");
        Player survivor2 = MockPlayerHelper.createMockPlayer(UUID.randomUUID(), "SurvivorTwo");

        victim.setStatistic(Statistic.TIME_SINCE_REST, 73000);
        survivor1.setStatistic(Statistic.TIME_SINCE_REST, 85000);
        survivor2.setStatistic(Statistic.TIME_SINCE_REST, 100000);

        List<Player> participants = List.of(victim, survivor1, survivor2);

        // Transition everyone into Attempt N+1
        for (Player p : participants) {
            resetManager.prepareForNewAttempt(p, spawnLocation);
        }

        for (Player p : participants) {
            assertEquals(0, p.getStatistic(Statistic.TIME_SINCE_REST),
                "Player " + p.getName() + " must have TIME_SINCE_REST reset to 0 in new attempt");
            assertEquals(GameMode.SURVIVAL, p.getGameMode());
        }
    }

    @Test
    @DisplayName("Administrative reset prepares all players for fresh attempt with zeroed insomnia")
    void adminResetPreparesAllPlayersWithZeroedInsomnia() {
        Player player1 = MockPlayerHelper.createMockPlayer(UUID.randomUUID(), "AdminPlayer1");
        Player player2 = MockPlayerHelper.createMockPlayer(UUID.randomUUID(), "AdminPlayer2");

        player1.setStatistic(Statistic.TIME_SINCE_REST, 90000);
        player2.setStatistic(Statistic.TIME_SINCE_REST, 85000);

        List<Player> onlinePlayers = List.of(player1, player2);

        // Rotation transfers every player using prepareForNewAttempt()
        for (Player p : onlinePlayers) {
            resetManager.prepareForNewAttempt(p, spawnLocation);
        }

        for (Player p : onlinePlayers) {
            assertEquals(0, p.getStatistic(Statistic.TIME_SINCE_REST),
                "Administrative reset must reset insomnia for " + p.getName());
            assertEquals(GameMode.SURVIVAL, p.getGameMode());
        }
    }

    @Test
    @DisplayName("prepareForCountdown does not reset TIME_SINCE_REST")
    void prepareForCountdownDoesNotResetInsomnia() {
        Player player = MockPlayerHelper.createMockPlayer(UUID.randomUUID(), "SpectatorPlayer");
        player.setStatistic(Statistic.TIME_SINCE_REST, 60000);

        resetManager.prepareForCountdown(player);

        assertEquals(60000, player.getStatistic(Statistic.TIME_SINCE_REST),
            "Entering countdown / spectator phase must not reset insomnia");
        assertEquals(GameMode.SPECTATOR, player.getGameMode(),
            "Player must be in SPECTATOR mode during countdown");
    }

    @Test
    @DisplayName("prepareForCountdown with focus location does not reset TIME_SINCE_REST")
    void prepareForCountdownWithLocationDoesNotResetInsomnia() {
        Player player = MockPlayerHelper.createMockPlayer(UUID.randomUUID(), "DyingSurvivor");
        player.setStatistic(Statistic.TIME_SINCE_REST, 75000);

        Location deathLocation = new Location(mockWorld, 200, 70, 200);
        resetManager.prepareForCountdown(player, deathLocation);

        assertEquals(75000, player.getStatistic(Statistic.TIME_SINCE_REST),
            "Spectating death location must not clear insomnia prematurely");
    }

    @Test
    @DisplayName("resetInsomniaState rejects null player")
    void resetInsomniaStateRejectsNull() {
        assertThrows(NullPointerException.class, () -> resetManager.resetInsomniaState(null));
    }

    @Test
    @DisplayName("prepareForNewAttempt rejects null arguments")
    void prepareForNewAttemptRejectsNull() {
        Player player = MockPlayerHelper.createMockPlayer(UUID.randomUUID(), "ValidPlayer");
        assertThrows(NullPointerException.class, () -> resetManager.prepareForNewAttempt(null, spawnLocation));
        assertThrows(NullPointerException.class, () -> resetManager.prepareForNewAttempt(player, null));
    }
}
