package io.github.checkselfy.bakemechanisms.profiling;

import static org.junit.jupiter.api.Assertions.*;
import io.github.checkselfy.bakemechanisms.marking.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProfilingIsolationTest {
    private final UUID world = UUID.randomUUID();
    private final UUID creator = UUID.randomUUID();
    private final Cuboid mechanism = new Cuboid(p(0, 64, 0), p(2, 68, 2));
    private final OutputContainer output = new OutputContainer(OutputContainer.Type.BARREL, Set.of(p(3, 64, 1)));

    @Test void markingRequiresEveryHalfAdjacentButLegacyRecoveryStillWorks() {
        var registry = new FarmRegistry(1000);
        var far = new OutputContainer(OutputContainer.Type.CHEST, Set.of(p(8, 64, 1)));
        assertThrows(IllegalArgumentException.class, () -> registry.register(creator, mechanism, far));
        assertTrue(registry.all().isEmpty());
        var doubleFar = new OutputContainer(OutputContainer.Type.DOUBLE_CHEST, Set.of(p(3, 64, 1), p(4, 64, 1)));
        assertThrows(IllegalArgumentException.class, () -> registry.register(creator, mechanism, doubleFar));
        var saved = new FarmRegistry.MarkedFarm(UUID.randomUUID(), creator, mechanism, far,
                FarmRegistry.State.NEEDS_PROFILING, null);
        registry.restore(saved);
        var service = new ProfilingService(registry, new ProfilingServiceTest.FakeWorld(), ProfilingSettings.DEFAULT);
        assertThrows(IllegalArgumentException.class, () -> service.start(saved.id(), creator));
        assertEquals(saved, registry.requireCreator(saved.id(), creator));
    }

    @Test void allPhysicalFacesAndNegativeCoordinatesHaveTheSameDistanceRule() {
        for (var point : List.of(p(-1,64,0),p(3,64,0),p(0,63,0),p(0,69,0),p(0,64,-1),p(0,64,3))) {
            assertDoesNotThrow(() -> new FarmRegistry(1000).register(creator, mechanism,
                    new OutputContainer(OutputContainer.Type.BARREL, Set.of(point))));
        }
        var extremes = new Cuboid(p(Integer.MIN_VALUE,64,0), p(Integer.MAX_VALUE,64,0)).expanded(2);
        assertEquals(Integer.MIN_VALUE, extremes.min().x());
        assertEquals(Integer.MAX_VALUE, extremes.max().x());
    }

    @Test void onlyMechanismCanFeedProductionAndOutputsCanOnlyDrain() {
        var isolation = isolation();
        assertTrue(isolation.transferAllowed(Set.of(p(1,64,1)), output.blocks()));
        assertTrue(isolation.transferAllowed(Set.of(p(1,64,1)), Set.of(p(2,64,1))));
        assertFalse(isolation.transferAllowed(Set.of(p(3,65,1)), output.blocks()), "neighbour hopper outside mechanism");
        assertFalse(isolation.transferAllowed(Set.of(p(5,64,1)), Set.of(p(4,64,1))), "outside -> belt ingress");
        assertFalse(isolation.transferAllowed(Set.of(p(4,64,1)), Set.of(p(2,64,1))), "returning chain -> mechanism");
        assertTrue(isolation.transferAllowed(output.blocks(), Set.of(p(3,63,1))), "normal unloading");
        assertFalse(isolation.transferAllowed(output.blocks(), Set.of(p(2,64,1))), "output baseline cannot be recirculated");
        assertFalse(isolation.transferAllowed(output.blocks(), output.blocks()));
    }

    @Test void playerDropAndMixedOriginItemsNeverBecomeProduction() {
        var isolation = isolation();
        var produced = UUID.randomUUID();
        isolation.produced(produced);
        assertTrue(isolation.trusted(produced));
        var thrown = UUID.randomUUID();
        isolation.playerDropped(thrown); // Actual Paper order: player event before item spawn.
        isolation.produced(thrown);
        assertFalse(isolation.trusted(thrown));
        var later = isolation();
        assertFalse(later.trusted(produced), "trust never survives cancellation or another run");
    }

    @Test void initialEquipmentIsAmbiguousWithoutBlockingOtherFarmProducts() {
        var isolation = isolation();
        isolation.initialize(new ProfilingIsolation.Sources(Set.of(), Set.of(org.bukkit.Material.IRON_HELMET)));
        assertTrue(isolation.ambiguous(org.bukkit.Material.IRON_HELMET));
        assertTrue(isolation.ambiguous(org.bukkit.Material.SHEARS));
        assertFalse(isolation.ambiguous(org.bukkit.Material.IRON_INGOT));
        assertFalse(isolation.ambiguous(org.bukkit.Material.WHITE_WOOL));
    }

    private ProfilingIsolation isolation() { return new ProfilingIsolation(new FarmRegistry(1000).register(creator, mechanism, output)); }
    private BlockPosition p(int x,int y,int z) { return new BlockPosition(world,x,y,z); }
}
