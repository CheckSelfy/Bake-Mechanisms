package io.github.checkselfy.bakemechanisms.emulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.checkselfy.bakemechanisms.emulation.StasisEmulationServiceTest.FakeStore;
import io.github.checkselfy.bakemechanisms.emulation.StasisEmulationServiceTest.FakeWorld;
import io.github.checkselfy.bakemechanisms.marking.BlockPosition;
import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.MarkedFarm;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.State;
import io.github.checkselfy.bakemechanisms.marking.OutputContainer;
import io.github.checkselfy.bakemechanisms.profiling.ChunkTickets;
import io.github.checkselfy.bakemechanisms.profiling.ProductionProfile;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * #12 regression: the {@code gold1} profile persisted during the 2026-11-12 benchmark, and the exact arithmetic
 * "profile rate x elapsed game ticks / 20" with fractional carry. The benchmark reported 137 rotten flesh and
 * 5 chicken after 61 ticks; the profile allows about 7.63 and 0.31.
 */
class Gold1RateRegressionTest {
    private static final double ROTTEN_FLESH_RATE = 2.5027932960893855;
    private static final double CHICKEN_RATE = 0.1005586592178771;
    private static final Map<Material, Double> GOLD1 = Map.of(
            Material.ROTTEN_FLESH, ROTTEN_FLESH_RATE, Material.CHICKEN, CHICKEN_RATE);
    private static final UUID WORLD = UUID.randomUUID();

    private final FakeWorld world = new FakeWorld();
    private final FakeStore store = new FakeStore();
    private final ChunkTickets tickets = new ChunkTickets(world);
    private final OutputContainer doubleChest = new OutputContainer(OutputContainer.Type.DOUBLE_CHEST,
            Set.of(new BlockPosition(WORLD, -1, 71, 5), new BlockPosition(WORLD, -1, 71, 6)));
    private final MarkedFarm farm = new MarkedFarm(UUID.randomUUID(), UUID.randomUUID(), "gold1",
            new Cuboid(new BlockPosition(WORLD, 0, 64, 0), new BlockPosition(WORLD, 12, 88, 12)),
            java.util.List.of(doubleChest), State.STASIS, new ProductionProfile(GOLD1, 3580));

    @Test
    void sixtyOneGameTicksOfGold1FromAFreshStasisYieldExactlySevenRottenFleshAndNoChicken() {
        var service = service(EmulationSettings.DEFAULT);
        world.outputs.put(doubleChest, new EnumMap<>(Material.class));
        service.start(farm, EmulationState.EMPTY);
        for (int tick = 0; tick < 61; tick++) service.tick();

        // Three 20-tick updates: 3 x 2.5027932960893855 = 7.5083798882681565, 3 x 0.1005586592178771 = 0.3016...
        assertEquals(Map.of(Material.ROTTEN_FLESH, 7), world.items(doubleChest));
        var fractions = store.emulation.get(farm.id()).outputs().getFirst().fractions();
        assertEquals(0, new BigDecimal("0.5083798882681565").compareTo(fractions.get(Material.ROTTEN_FLESH)));
        assertEquals(0, new BigDecimal("0.3016759776536313").compareTo(fractions.get(Material.CHICKEN)));
    }

    @Test
    void anySixtyOneTickWindowMatchesTheProfileWithinOneUpdate() {
        var service = service(EmulationSettings.DEFAULT);
        world.outputs.put(doubleChest, new EnumMap<>(Material.class));
        service.start(farm, EmulationState.EMPTY);
        var before = new HashMap<Integer, Map<Material, Integer>>();
        for (int tick = 0; tick <= 2_000; tick++) {
            before.put(tick, world.items(doubleChest));
            service.tick();
        }
        for (int start = 1_000; start < 1_020; start++) {
            var window = delta(before.get(start), before.get(start + 61));
            int expectedFlesh = expected(ROTTEN_FLESH_RATE, start + 61) - expected(ROTTEN_FLESH_RATE, start);
            int expectedChicken = expected(CHICKEN_RATE, start + 61) - expected(CHICKEN_RATE, start);
            assertEquals(expectedFlesh, window.getOrDefault(Material.ROTTEN_FLESH, 0), "window starting at " + start);
            assertEquals(expectedChicken, window.getOrDefault(Material.CHICKEN, 0), "window starting at " + start);
            // A 61-tick window contains three or four updates: never more than 4 x 2.5028 rotten flesh.
            assertTrue(expectedFlesh >= 7 && expectedFlesh <= 11, "rotten flesh in 61 ticks: " + expectedFlesh);
            assertTrue(expectedChicken <= 1, "chicken in 61 ticks: " + expectedChicken);
        }
    }

    @Test
    void twelveHundredGameTicksYieldTheExactProfileForEveryUpdateInterval() {
        for (int interval : new int[] { 1, 8, 20, 60, 600, 1200 }) {
            var fresh = new FakeWorld();
            fresh.outputs.put(doubleChest, new EnumMap<>(Material.class));
            var service = new StasisEmulationService(fresh, new ChunkTickets(fresh), new EmulationSettings(interval),
                    new FakeStore());
            service.start(farm, EmulationState.EMPTY);
            for (int tick = 0; tick < 1_200; tick++) service.tick();
            // 60 x 2.5027932960893855 = 150.1675977653631, 60 x 0.1005586592178771 = 6.033519553072626
            assertEquals(Map.of(Material.ROTTEN_FLESH, 150, Material.CHICKEN, 6), fresh.items(doubleChest),
                    "update interval " + interval);
        }
        // An interval that does not divide the window credits only its completed updates: 171 x 7 = 1197 ticks.
        var partial = new FakeWorld();
        partial.outputs.put(doubleChest, new EnumMap<>(Material.class));
        var service = new StasisEmulationService(partial, new ChunkTickets(partial), new EmulationSettings(7),
                new FakeStore());
        service.start(farm, EmulationState.EMPTY);
        for (int tick = 0; tick < 1_200; tick++) service.tick();
        // 2.5027932960893855 x 1197 / 20 = 149.79..., 0.1005586592178771 x 1197 / 20 = 6.018...
        assertEquals(Map.of(Material.ROTTEN_FLESH, 149, Material.CHICKEN, 6), partial.items(doubleChest));
    }

    @Test
    void fractionalCarryAcrossARestartLosesAndRecreditsNothing() {
        var uninterrupted = new FakeWorld();
        uninterrupted.outputs.put(doubleChest, new EnumMap<>(Material.class));
        var reference = new StasisEmulationService(uninterrupted, new ChunkTickets(uninterrupted),
                EmulationSettings.DEFAULT, new FakeStore());
        reference.start(farm, EmulationState.EMPTY);
        for (int tick = 0; tick < 1_200; tick++) reference.tick();

        world.outputs.put(doubleChest, new EnumMap<>(Material.class));
        var first = service(EmulationSettings.DEFAULT);
        first.start(farm, EmulationState.EMPTY);
        for (int tick = 0; tick < 60; tick++) first.tick();
        first.stopAll();
        var restarted = service(EmulationSettings.DEFAULT);
        restarted.start(farm, store.emulation.get(farm.id()));
        for (int tick = 0; tick < 1_140; tick++) restarted.tick();
        assertEquals(uninterrupted.items(doubleChest), world.items(doubleChest));
    }

    private StasisEmulationService service(EmulationSettings settings) {
        return new StasisEmulationService(world, tickets, settings, store);
    }

    /** Whole items after {@code ticks} game ticks from an empty state, updated every 20 ticks. */
    private static int expected(double rate, int ticks) {
        return BigDecimal.valueOf(rate).multiply(BigDecimal.valueOf(ticks / 20))
                .setScale(0, RoundingMode.FLOOR).intValueExact();
    }

    private static Map<Material, Integer> delta(Map<Material, Integer> before, Map<Material, Integer> after) {
        var result = new EnumMap<Material, Integer>(Material.class);
        after.forEach((type, amount) -> result.put(type, amount - before.getOrDefault(type, 0)));
        return result;
    }
}
