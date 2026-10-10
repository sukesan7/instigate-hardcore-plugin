package dev.instigatehardcore.replay;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class ReplayCaptureModelsTest {
    private final UUID player = UUID.randomUUID();
    private final UUID enemy = UUID.randomUUID();
    private final UUID world = UUID.randomUUID();

    private ReplayCombatEvent event(long tick, int attempt, UUID inWorld) {
        return new ReplayCombatEvent(tick, attempt, inWorld, player, "PLAYER",
            enemy, "ZOMBIE", "ENTITY_ATTACK", 3.0, 20, 64, 20);
    }

    private ReplayClip clip() {
        ReplayActorSnapshot actor = new ReplayActorSnapshot(
            player, "PLAYER", "Tester", 20, 64, 20, 0, 0,
            false, false, false, 4, ReplayEquipment.empty()
        );
        return new ReplayClip(3, player, world, List.of(
            new ReplayFrame(100, 3, world, player, List.of(actor)),
            new ReplayFrame(102, 3, world, player, List.of(actor))
        ));
    }

    private ReplayDeathMoment death() {
        return new ReplayDeathMoment(3, player, world, 104,
            1000L, 21, 64, 20, 80, 0, "Tester was slain by Zombie", "Zombie");
    }

    @Test
    void collectsEventsWithinRequestedWindow() {
        RollingReplayEventBuffer buffer = new RollingReplayEventBuffer(10, 140);
        buffer.append(event(20, 3, world));
        buffer.append(event(100, 3, world));
        buffer.append(event(102, 3, world));
        assertEquals(List.of(100L, 102L), buffer.snapshot(3, world, 100, 102)
            .stream().map(ReplayCombatEvent::tick).toList());
    }

    @Test
    void keepsOnlyMostRecentEvents() {
        RollingReplayEventBuffer buffer = new RollingReplayEventBuffer(3, 140);
        for (int t = 1; t <= 5; t++) buffer.append(event(t, 3, world));
        assertEquals(3, buffer.size());
        assertEquals(3L, buffer.snapshot(3, world, 0, 10).getFirst().tick());
    }

    @Test
    void prunesEventsOutsideWindow() {
        RollingReplayEventBuffer buffer = new RollingReplayEventBuffer(50, 10);
        buffer.append(event(10, 3, world));
        buffer.append(event(20, 3, world));
        buffer.append(event(21, 3, world));
        assertEquals(2, buffer.size());
    }

    @Test
    void dimensionAndAttemptChangesDiscardOldEvents() {
        RollingReplayEventBuffer buffer = new RollingReplayEventBuffer(50, 140);
        UUID nether = UUID.randomUUID();
        buffer.append(event(100, 3, world));
        buffer.append(event(102, 3, nether));
        assertEquals(0, buffer.snapshot(3, world, 0, 200).size());
        buffer.append(event(104, 4, nether));
        assertEquals(0, buffer.snapshot(3, nether, 0, 200).size());
    }

    @Test
    void immutableFrozenDeathAndEventLists() {
        List<ReplayCombatEvent> events = new ArrayList<>();
        events.add(event(102, 3, world));
        FrozenDeathReplay frozen = new FrozenDeathReplay(clip(), death(), events);
        events.clear();
        assertEquals(1, frozen.combatEvents().size());
        assertThrows(UnsupportedOperationException.class,
            () -> frozen.combatEvents().clear());
    }

    @Test
    void rejectsEventsFromDifferentWorldOrAttempt() {
        assertThrows(IllegalArgumentException.class, () -> new FrozenDeathReplay(
            clip(), death(), List.of(event(102, 3, UUID.randomUUID()))));
        assertThrows(IllegalArgumentException.class, () -> new FrozenDeathReplay(
            clip(), death(), List.of(event(102, 4, world))));
    }

    @Test
    void rejectsEventOutsideClipTimeRange() {
        assertThrows(IllegalArgumentException.class, () -> new FrozenDeathReplay(
            clip(), death(), List.of(event(99, 3, world))));
        assertThrows(IllegalArgumentException.class, () -> new FrozenDeathReplay(
            clip(), death(), List.of(event(105, 3, world))));
    }

    @Test
    void rejectsMismatchedDeathWorldOrAttempt() {
        ReplayDeathMoment wrongWorld = new ReplayDeathMoment(3, player,
            UUID.randomUUID(), 104, 1000, 21, 64, 20, 80, 0, "died", "fall");
        assertThrows(IllegalArgumentException.class,
            () -> new FrozenDeathReplay(clip(), wrongWorld, List.of()));
    }

    @Test
    void environmentalDeathDoesNotNeedAttackerUuid() {
        ReplayCombatEvent environment = new ReplayCombatEvent(101, 3, world,
            player, "PLAYER", null, "ENVIRONMENT", "FALL", 30, 21, 64, 20);
        assertNull(environment.attackerEntityId());
        assertEquals(1, new FrozenDeathReplay(clip(), death(),
            List.of(environment)).combatEvents().size());
    }

    @Test
    void rejectsInvalidDamageValues() {
        assertThrows(IllegalArgumentException.class, () -> new ReplayCombatEvent(
            102, 3, world, player, "PLAYER", null, "ENVIRONMENT", "FALL",
            Double.NaN, 1, 2, 3));
    }
}
