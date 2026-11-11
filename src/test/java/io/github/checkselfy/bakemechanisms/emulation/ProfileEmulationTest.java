package io.github.checkselfy.bakemechanisms.emulation;

import static org.junit.jupiter.api.Assertions.*;

import io.github.checkselfy.bakemechanisms.profiling.ProductionProfile;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class ProfileEmulationTest {
    private final FakeOutput output = new FakeOutput();

    @Test
    void fractionalRateYieldsWholeItemOnlyOnceAccumulated() {
        var emulation = new ProfileEmulation(new ProductionProfile(Map.of(Material.COBBLESTONE, 0.1), 3600));
        for (int i = 0; i < 9; i++) emulation.update(20, output.only());
        assertEquals(Map.of(), output.items);
        emulation.update(20, output.only());
        assertEquals(Map.of(Material.COBBLESTONE, 1), output.items);
    }

    @Test
    void restoredStateContinuesWithoutResettingOrRecreditingOutput() {
        var profile = new ProductionProfile(Map.of(Material.KELP, 0.7, Material.BAMBOO, 3.0), 3600);
        var original = new ProfileEmulation(profile);
        output.capacity.put(Material.BAMBOO, 2);
        original.update(20, output.only()); // kelp 0.7 accumulated, bamboo 1 waits in the buffer
        var saved = original.state();
        assertEquals(Map.of(Material.BAMBOO, 1), saved.outputs().get(0).buffer());

        var restored = new ProfileEmulation(profile, saved);
        assertTrue(restored.paused(), "buffered items still block production");
        output.capacity.clear();
        restored.update(20, output.only()); // buffer flushes, kelp 0.7 + 0.7 yields one item
        assertEquals(Map.of(Material.BAMBOO, 6, Material.KELP, 1), output.items);
        assertEquals(new BigDecimal("0.4"), restored.state().outputs().get(0).fractions().get(Material.KELP));
    }

    @Test
    void stateOfAnotherProfileIsRejected() {
        var state = new EmulationState(Map.of(Material.KELP, new BigDecimal("0.5")), Map.of());
        var profile = new ProductionProfile(Map.of(Material.BAMBOO, 1.0), 3600);
        assertThrows(IllegalArgumentException.class, () -> new ProfileEmulation(profile, state));
        assertThrows(IllegalArgumentException.class,
                () -> new EmulationState(Map.of(Material.KELP, BigDecimal.ONE), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new EmulationState(Map.of(), Map.of(Material.KELP, 0)));
    }

    @Test
    void longRunsMatchTheProfileExactlyForEveryTypeAndInterval() {
        var profile = new ProductionProfile(Map.of(Material.BAMBOO, 0.35, Material.SUGAR_CANE, 1.7), 3600);
        var emulation = new ProfileEmulation(profile);
        for (int i = 0; i < 10_000; i++) emulation.update(7, output.only()); // 70 000 ticks
        assertEquals(Map.of(Material.BAMBOO, 1225, Material.SUGAR_CANE, 5950), output.items);
    }

    @Test
    void fullOutputPausesProductionWithoutLosingItemsAndResumesWhenFreed() {
        var emulation = new ProfileEmulation(new ProductionProfile(Map.of(Material.KELP, 3.0), 3600));
        output.capacity.put(Material.KELP, 4);
        emulation.update(20, output.only());
        emulation.update(20, output.only());
        assertEquals(Map.of(Material.KELP, 4), output.items);
        assertEquals(Map.of(Material.KELP, 2), emulation.buffer());
        assertTrue(emulation.paused());

        for (int i = 0; i < 50; i++) emulation.update(20, output.only());
        assertEquals(Map.of(Material.KELP, 2), emulation.buffer(), "paused emulation keeps producing");

        output.items.put(Material.KELP, 1); // a player or hopper took three items
        emulation.update(20, output.only()); // buffered 2 fit, production resumes: 1 of 3 new fits
        assertEquals(Map.of(Material.KELP, 4), output.items);
        assertEquals(Map.of(Material.KELP, 2), emulation.buffer());

        output.capacity.clear();
        emulation.update(20, output.only());
        assertEquals(Map.of(Material.KELP, 4 + 2 + 3), output.items);
        assertTrue(emulation.buffer().isEmpty());
        assertFalse(emulation.paused());
    }

    @Test
    void oneFullOutputPausesProductionForAllWhileOthersReceiveTheirOwnBuffer() {
        var second = new FakeOutput();
        ProfileEmulation.Output both = (index, type, amount) ->
                (index == 0 ? output : second).insert(type, amount);
        var emulation = new ProfileEmulation(new ProductionProfile(
                List.of(Map.of(Material.KELP, 3.0), Map.of(Material.BAMBOO, 2.0)), 3600));
        output.capacity.put(Material.KELP, 4);
        second.capacity.put(Material.BAMBOO, 1);
        emulation.update(20, both); // kelp 3 fit; bamboo 1 of 2 fits
        emulation.update(20, both); // paused by bamboo: nothing new is produced for kelp either
        assertEquals(List.of(1), emulation.blockedOutputs());
        assertEquals(Map.of(Material.KELP, 3), output.items);
        assertEquals(List.of(Map.of(), Map.of(Material.BAMBOO, 1)), emulation.state().outputs().stream()
                .map(EmulationState.OutputState::buffer).toList());

        second.capacity.clear();
        emulation.update(20, both); // the bamboo buffer is delivered, then both outputs produce again
        assertEquals(Map.of(Material.KELP, 4), output.items);
        assertEquals(Map.of(Material.KELP, 2), emulation.buffer(), "kelp waits once its own output is full");
        assertEquals(Map.of(Material.BAMBOO, 4), second.items);
        assertEquals(List.of(0), emulation.blockedOutputs());

        second.capacity.put(Material.BAMBOO, 4);
        output.capacity.clear();
        output.items.clear(); // kelp output emptied: its buffer drains and production resumes until bamboo fills
        emulation.update(20, both);
        assertEquals(Map.of(Material.KELP, 2 + 3), output.items);
        assertEquals(List.of(1), emulation.blockedOutputs());
    }

    @Test
    void missingOutputDoesNotStopDeliveryToTheOthers() {
        var second = new FakeOutput();
        var emulation = new ProfileEmulation(new ProductionProfile(
                List.of(Map.of(Material.KELP, 1.0), Map.of(Material.BAMBOO, 1.0)), 3600));
        assertThrows(IllegalStateException.class, () -> emulation.update(20, (index, type, amount) -> {
            if (index == 0) throw new IllegalStateException("gone");
            return second.insert(type, amount);
        }));
        assertEquals(Map.of(Material.BAMBOO, 1), second.items);
        assertEquals(Map.of(Material.KELP, 1), emulation.buffer());
    }

    /** Accepts everything up to a per-type capacity; unlimited when no capacity is set. */
    static final class FakeOutput {
        final Map<Material, Integer> items = new EnumMap<>(Material.class);
        final Map<Material, Integer> capacity = new EnumMap<>(Material.class);

        /** This container as the single output of a farm. */
        ProfileEmulation.Output only() {
            return (index, type, amount) -> {
                assertEquals(0, index);
                return insert(type, amount);
            };
        }

        int insert(Material type, int amount) {
            int room = capacity.getOrDefault(type, Integer.MAX_VALUE) - items.getOrDefault(type, 0);
            int accepted = Math.max(0, Math.min(room, amount));
            if (accepted > 0) items.merge(type, accepted, Integer::sum);
            return accepted;
        }
    }
}
