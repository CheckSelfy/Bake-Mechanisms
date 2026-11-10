package io.github.checkselfy.bakemechanisms.profiling;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class ProfileCalculatorTest {
    private static final ProfilingSettings DEFAULTS = ProfilingSettings.DEFAULT;

    @Test
    void initialOutputContentIsNotProduction() {
        var calculator = new ProfileCalculator(snapshot(Map.of(Material.COBBLESTONE, 10, Material.DIRT, 5), false),
                DEFAULTS);
        calculator.record(3600, snapshot(Map.of(Material.COBBLESTONE, 190, Material.DIRT, 5), false));
        var profile = accepted(calculator.result());
        assertEquals(3600, profile.usefulTicks());
        // 180 items over 3600 ticks = 1 item per 20 ticks.
        assertEquals(Map.of(Material.COBBLESTONE, 1.0), profile.ratesPer20Ticks());
    }

    @Test
    void segmentEndsBeforeFirstFullSnapshotButKeepsEarlierPauses() {
        var calculator = new ProfileCalculator(snapshot(Map.of(), false), DEFAULTS);
        calculator.record(20, snapshot(Map.of(Material.BONE, 4), false));
        calculator.record(40, snapshot(Map.of(Material.BONE, 4), false)); // ordinary pause
        calculator.record(60, snapshot(Map.of(Material.BONE, 4), false));
        calculator.record(80, snapshot(Map.of(Material.BONE, 8), false));
        calculator.record(100, snapshot(Map.of(Material.BONE, 9), true));
        calculator.record(120, snapshot(Map.of(Material.BONE, 3), false)); // after saturation: ignored
        var profile = accepted(calculator.result());
        assertEquals(80, profile.usefulTicks());
        assertEquals(Map.of(Material.BONE, 2.0), profile.ratesPer20Ticks());
    }

    @Test
    void profileWithoutProducedItemsIsRejected() {
        var idle = new ProfileCalculator(snapshot(Map.of(Material.DIRT, 3), false), DEFAULTS);
        idle.record(3600, snapshot(Map.of(Material.DIRT, 3), false));
        assertInstanceOf(ProfileCalculator.Result.Rejected.class, idle.result());

        var fullFromStart = new ProfileCalculator(snapshot(Map.of(Material.DIRT, 64), true), DEFAULTS);
        fullFromStart.record(20, snapshot(Map.of(Material.DIRT, 64), true));
        assertInstanceOf(ProfileCalculator.Result.Rejected.class, fullFromStart.result());

        var onlyRemovals = new ProfileCalculator(snapshot(Map.of(Material.DIRT, 10), false), DEFAULTS);
        onlyRemovals.record(3600, snapshot(Map.of(Material.DIRT, 2), false));
        assertInstanceOf(ProfileCalculator.Result.Rejected.class, onlyRemovals.result());
    }

    @Test
    void shortUsefulSegmentIsRejectedOnlyWithFeatureFlag() {
        for (boolean flag : new boolean[] { false, true }) {
            var calculator = new ProfileCalculator(snapshot(Map.of(), false),
                    new ProfilingSettings(20, 600, flag));
            calculator.record(580, snapshot(Map.of(Material.STRING, 29), false));
            calculator.record(600, snapshot(Map.of(Material.STRING, 30), true));
            var result = calculator.result();
            if (flag) {
                assertTrue(assertInstanceOf(ProfileCalculator.Result.Rejected.class, result).reason()
                        .contains("600"));
            } else {
                assertEquals(Map.of(Material.STRING, 1.0), accepted(result).ratesPer20Ticks());
            }
        }
        var exact = new ProfileCalculator(snapshot(Map.of(), false), new ProfilingSettings(20, 600, true));
        exact.record(600, snapshot(Map.of(Material.STRING, 30), false));
        assertEquals(600, accepted(exact.result()).usefulTicks());
    }

    private static OutputSnapshot snapshot(Map<Material, Integer> counts, boolean full) {
        return new OutputSnapshot(counts, full);
    }

    private static ProductionProfile accepted(ProfileCalculator.Result result) {
        return assertInstanceOf(ProfileCalculator.Result.Accepted.class, result).profile();
    }
}
