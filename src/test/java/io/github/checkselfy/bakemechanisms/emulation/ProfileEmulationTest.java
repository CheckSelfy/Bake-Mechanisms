package io.github.checkselfy.bakemechanisms.emulation;

import static org.junit.jupiter.api.Assertions.*;

import io.github.checkselfy.bakemechanisms.profiling.ProductionProfile;
import java.util.EnumMap;
import java.util.Map;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class ProfileEmulationTest {
    private final FakeOutput output = new FakeOutput();

    @Test
    void fractionalRateYieldsWholeItemOnlyOnceAccumulated() {
        var emulation = new ProfileEmulation(new ProductionProfile(Map.of(Material.COBBLESTONE, 0.1), 3600));
        for (int i = 0; i < 9; i++) emulation.update(20, output);
        assertEquals(Map.of(), output.items);
        emulation.update(20, output);
        assertEquals(Map.of(Material.COBBLESTONE, 1), output.items);
    }

    @Test
    void longRunsMatchTheProfileExactlyForEveryTypeAndInterval() {
        var profile = new ProductionProfile(Map.of(Material.BAMBOO, 0.35, Material.SUGAR_CANE, 1.7), 3600);
        var emulation = new ProfileEmulation(profile);
        for (int i = 0; i < 10_000; i++) emulation.update(7, output); // 70 000 ticks
        assertEquals(Map.of(Material.BAMBOO, 1225, Material.SUGAR_CANE, 5950), output.items);
    }

    @Test
    void fullOutputPausesProductionWithoutLosingItemsAndResumesWhenFreed() {
        var emulation = new ProfileEmulation(new ProductionProfile(Map.of(Material.KELP, 3.0), 3600));
        output.capacity.put(Material.KELP, 4);
        emulation.update(20, output);
        emulation.update(20, output);
        assertEquals(Map.of(Material.KELP, 4), output.items);
        assertEquals(Map.of(Material.KELP, 2), emulation.buffer());
        assertTrue(emulation.paused());

        for (int i = 0; i < 50; i++) emulation.update(20, output);
        assertEquals(Map.of(Material.KELP, 2), emulation.buffer(), "paused emulation keeps producing");

        output.items.put(Material.KELP, 1); // a player or hopper took three items
        emulation.update(20, output); // buffered 2 fit, production resumes: 1 of 3 new fits
        assertEquals(Map.of(Material.KELP, 4), output.items);
        assertEquals(Map.of(Material.KELP, 2), emulation.buffer());

        output.capacity.clear();
        emulation.update(20, output);
        assertEquals(Map.of(Material.KELP, 4 + 2 + 3), output.items);
        assertTrue(emulation.buffer().isEmpty());
        assertFalse(emulation.paused());
    }

    /** Accepts everything up to a per-type capacity; unlimited when no capacity is set. */
    static final class FakeOutput implements ProfileEmulation.Output {
        final Map<Material, Integer> items = new EnumMap<>(Material.class);
        final Map<Material, Integer> capacity = new EnumMap<>(Material.class);

        @Override
        public int insert(Material type, int amount) {
            int room = capacity.getOrDefault(type, Integer.MAX_VALUE) - items.getOrDefault(type, 0);
            int accepted = Math.max(0, Math.min(room, amount));
            if (accepted > 0) items.merge(type, accepted, Integer::sum);
            return accepted;
        }
    }
}
