package io.github.checkselfy.bakemechanisms.profiling;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class ProfileCalculatorTest {
    private static final ProfilingSettings DEFAULTS = ProfilingSettings.DEFAULT;

    @Test
    void movingInitialHopperStockIntoOutputNeverCreatesProduction() {
        var calculator = new ProfileCalculator(List.of(snapshot(Map.of(Material.SUGAR_CANE,10),false)),
                Map.of(Material.SUGAR_CANE,320L),DEFAULTS);
        calculator.record(20,List.of(snapshot(Map.of(Material.SUGAR_CANE,74),false)),Map.of(Material.SUGAR_CANE,256L));
        calculator.record(3600,List.of(snapshot(Map.of(Material.SUGAR_CANE,330),false)),Map.of());
        assertInstanceOf(ProfileCalculator.Result.Rejected.class,calculator.result());
    }

    @Test
    void newItemsRemainingInHoppersContributeToTheSameNetStock() {
        var calculator = new ProfileCalculator(List.of(snapshot(Map.of(Material.SUGAR_CANE,10),false)),
                Map.of(Material.SUGAR_CANE,20L),DEFAULTS);
        calculator.record(20,List.of(snapshot(Map.of(Material.SUGAR_CANE,15),false)),Map.of(Material.SUGAR_CANE,16L));
        calculator.record(220,List.of(snapshot(Map.of(Material.SUGAR_CANE,33),false)),Map.of(Material.SUGAR_CANE,18L));
        assertEquals(Map.of(Material.SUGAR_CANE,2.0),accepted(calculator.result()).ratesPer20Ticks()); // (51 - 31) / 200 * 20
    }

    @Test
    void initialStockReturningAfterATemporaryDeficitCannotInflateTheProfile() {
        var calculator = new ProfileCalculator(List.of(snapshot(Map.of(),false)),Map.of(Material.SUGAR_CANE,320L),DEFAULTS);
        calculator.record(20,List.of(snapshot(Map.of(Material.DIAMOND,1),false)),Map.of()); // Old stock temporarily elsewhere.
        calculator.record(220,List.of(snapshot(Map.of(Material.SUGAR_CANE,320,Material.DIAMOND,11),false)),Map.of());
        assertEquals(Map.of(Material.DIAMOND,1.0),accepted(calculator.result()).ratesPer20Ticks());
    }

    @Test
    void sharedHopperStockWithIndistinguishableOutputDestinationsIsRejected() {
        var calculator = new ProfileCalculator(List.of(snapshot(Map.of(),false),snapshot(Map.of(),false)),
                Map.of(Material.SUGAR_CANE,64L),DEFAULTS);
        calculator.record(20,List.of(snapshot(Map.of(Material.SUGAR_CANE,1),false),snapshot(Map.of(),false)),Map.of(Material.SUGAR_CANE,64L));
        calculator.record(220,List.of(snapshot(Map.of(Material.SUGAR_CANE,6),false),snapshot(Map.of(Material.SUGAR_CANE,5),false)),Map.of(Material.SUGAR_CANE,64L));
        assertTrue(assertInstanceOf(ProfileCalculator.Result.Rejected.class,calculator.result()).reason().contains("однозначно"));
    }

    @Test
    void differentProductsWithHopperStockRemainAssignableToTheirOwnOutputs() {
        var calculator = new ProfileCalculator(List.of(snapshot(Map.of(Material.BONE,10),false),snapshot(Map.of(Material.ARROW,20),false)),
                Map.of(Material.BONE,64L,Material.ARROW,128L),DEFAULTS);
        calculator.record(20,List.of(snapshot(Map.of(Material.BONE,11),false),snapshot(Map.of(Material.ARROW,21),false)),
                Map.of(Material.BONE,64L,Material.ARROW,128L));
        calculator.record(220,List.of(snapshot(Map.of(Material.BONE,80),false),snapshot(Map.of(Material.ARROW,160),false)),
                Map.of(Material.BONE,15L,Material.ARROW,29L));
        assertEquals(List.of(Map.of(Material.BONE,2.0),Map.of(Material.ARROW,4.0)),accepted(calculator.result()).outputs());
    }

    @Test
    void farmSwitchedOnLateIsMeasuredFromItsFirstItemNotFromTheStartOfProfiling() {
        var calculator = new ProfileCalculator(snapshot(Map.of(), false), DEFAULTS);
        for (int tick = 20; tick <= 1000; tick += 20) calculator.record(tick, snapshot(Map.of(), false));
        // Switched on after tick 1000: one item every 20 ticks.
        for (int tick = 1020; tick <= 3600; tick += 20) {
            calculator.record(tick, snapshot(Map.of(Material.STRING, (tick - 1000) / 20), false));
        }
        var profile = accepted(calculator.result());
        assertEquals(Map.of(Material.STRING, 1.0), profile.ratesPer20Ticks());
        assertEquals(3600 - 1020, profile.usefulTicks());
    }

    @Test
    void initialOutputContentIsNotProduction() {
        var calculator = new ProfileCalculator(snapshot(Map.of(Material.COBBLESTONE, 10, Material.DIRT, 5), false),
                DEFAULTS);
        calculator.record(20, snapshot(Map.of(Material.COBBLESTONE, 11, Material.DIRT, 5), false));
        calculator.record(3600, snapshot(Map.of(Material.COBBLESTONE, 190, Material.DIRT, 5), false));
        var profile = accepted(calculator.result());
        assertEquals(3580, profile.usefulTicks());
        // 179 items over 3580 ticks = 1 item per 20 ticks.
        assertEquals(Map.of(Material.COBBLESTONE, 1.0), profile.ratesPer20Ticks());
    }

    @Test
    void segmentEndsBeforeFirstFullSnapshotButKeepsEarlierPauses() {
        var calculator = new ProfileCalculator(snapshot(Map.of(), false), DEFAULTS);
        calculator.record(20, snapshot(Map.of(Material.BONE, 4), false)); // first items: the segment starts
        calculator.record(40, snapshot(Map.of(Material.BONE, 4), false)); // ordinary pause
        calculator.record(60, snapshot(Map.of(Material.BONE, 4), false));
        calculator.record(80, snapshot(Map.of(Material.BONE, 10), false));
        calculator.record(100, snapshot(Map.of(Material.BONE, 11), true));
        calculator.record(120, snapshot(Map.of(Material.BONE, 3), false)); // after saturation: ignored
        var profile = accepted(calculator.result());
        assertEquals(60, profile.usefulTicks());
        assertEquals(Map.of(Material.BONE, 2.0), profile.ratesPer20Ticks());
    }

    @Test
    void profileWithoutAMeasurableSegmentIsRejected() {
        var idle = new ProfileCalculator(snapshot(Map.of(Material.DIRT, 3), false), DEFAULTS);
        idle.record(3600, snapshot(Map.of(Material.DIRT, 3), false));
        assertInstanceOf(ProfileCalculator.Result.Rejected.class, idle.result());

        var fullFromStart = new ProfileCalculator(snapshot(Map.of(Material.DIRT, 64), true), DEFAULTS);
        fullFromStart.record(20, snapshot(Map.of(Material.DIRT, 64), true));
        assertInstanceOf(ProfileCalculator.Result.Rejected.class, fullFromStart.result());

        var onlyRemovals = new ProfileCalculator(snapshot(Map.of(Material.DIRT, 10), false), DEFAULTS);
        onlyRemovals.record(3600, snapshot(Map.of(Material.DIRT, 2), false));
        assertInstanceOf(ProfileCalculator.Result.Rejected.class, onlyRemovals.result());

        var firstItemsAtTheEnd = new ProfileCalculator(snapshot(Map.of(), false), DEFAULTS);
        firstItemsAtTheEnd.record(3580, snapshot(Map.of(), false));
        firstItemsAtTheEnd.record(3600, snapshot(Map.of(Material.DIRT, 5), false));
        assertInstanceOf(ProfileCalculator.Result.Rejected.class, firstItemsAtTheEnd.result(),
                "no time was measured after the first items appeared");

        var filledRightAfterStart = new ProfileCalculator(snapshot(Map.of(), false), DEFAULTS);
        filledRightAfterStart.record(20, snapshot(Map.of(Material.DIRT, 5), false));
        filledRightAfterStart.record(40, snapshot(Map.of(Material.DIRT, 1728), true));
        assertInstanceOf(ProfileCalculator.Result.Rejected.class, filledRightAfterStart.result());
    }

    @Test
    void shortUsefulSegmentIsRejectedOnlyWithFeatureFlag() {
        for (boolean flag : new boolean[] { false, true }) {
            var calculator = new ProfileCalculator(snapshot(Map.of(), false),
                    new ProfilingSettings(20, 600, flag));
            calculator.record(20, snapshot(Map.of(Material.STRING, 1), false));
            calculator.record(600, snapshot(Map.of(Material.STRING, 30), false));
            calculator.record(620, snapshot(Map.of(Material.STRING, 31), true));
            var result = calculator.result();
            if (flag) {
                assertTrue(assertInstanceOf(ProfileCalculator.Result.Rejected.class, result).reason()
                        .contains("600"));
            } else {
                assertEquals(Map.of(Material.STRING, 1.0), accepted(result).ratesPer20Ticks());
            }
        }
        var exact = new ProfileCalculator(snapshot(Map.of(), false), new ProfilingSettings(20, 600, true));
        exact.record(20, snapshot(Map.of(Material.STRING, 1), false));
        exact.record(620, snapshot(Map.of(Material.STRING, 31), false));
        assertEquals(600, accepted(exact.result()).usefulTicks());
    }

    @Test
    void eachOutputIsMeasuredSeparatelyOverTheSegmentEndingWhenAnyOutputFills() {
        var calculator = new ProfileCalculator(List.of(snapshot(Map.of(Material.BONE, 2), false),
                snapshot(Map.of(), false)), DEFAULTS);
        calculator.record(20, List.of(snapshot(Map.of(Material.BONE, 2), false), snapshot(Map.of(), false)));
        // The segment of every output starts with the first items in any output.
        calculator.record(40, List.of(snapshot(Map.of(Material.BONE, 2), false),
                snapshot(Map.of(Material.ARROW, 1), false)));
        calculator.record(80, List.of(snapshot(Map.of(Material.BONE, 6), false),
                snapshot(Map.of(Material.ARROW, 2), false)));
        assertEquals(-1, calculator.fullOutput());
        // The second output fills first; the overflow that the first output receives afterwards is not counted.
        calculator.record(100, List.of(snapshot(Map.of(Material.BONE, 8), false),
                snapshot(Map.of(Material.ARROW, 3), true)));
        assertEquals(1, calculator.fullOutput());
        calculator.record(120, List.of(snapshot(Map.of(Material.BONE, 40), false),
                snapshot(Map.of(Material.ARROW, 3), true)));
        var profile = accepted(calculator.result());
        assertEquals(40, profile.usefulTicks());
        assertEquals(List.of(Map.of(Material.BONE, 2.0), Map.of(Material.ARROW, 0.5)), profile.outputs());
    }

    @Test
    void outputWithoutProductionIsKeptAsEmptyRates() {
        var calculator = new ProfileCalculator(List.of(snapshot(Map.of(), false), snapshot(Map.of(), false)),
                DEFAULTS);
        calculator.record(20, List.of(snapshot(Map.of(), false), snapshot(Map.of(Material.KELP, 1), false)));
        calculator.record(3600, List.of(snapshot(Map.of(), false), snapshot(Map.of(Material.KELP, 180), false)));
        assertEquals(List.of(Map.of(), Map.of(Material.KELP, 1.0)), accepted(calculator.result()).outputs());
    }

    private static OutputSnapshot snapshot(Map<Material, Integer> counts, boolean full) {
        return new OutputSnapshot(counts, full);
    }

    private static ProductionProfile accepted(ProfileCalculator.Result result) {
        return assertInstanceOf(ProfileCalculator.Result.Accepted.class, result).profile();
    }
}
