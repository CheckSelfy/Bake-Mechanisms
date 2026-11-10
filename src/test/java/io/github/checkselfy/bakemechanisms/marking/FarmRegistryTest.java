package io.github.checkselfy.bakemechanisms.marking;

import static org.junit.jupiter.api.Assertions.*;

import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.MarkedFarm;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.State;
import io.github.checkselfy.bakemechanisms.profiling.ProductionProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class FarmRegistryTest {
    private static final UUID WORLD = UUID.randomUUID();
    private final UUID creator = UUID.randomUUID();
    private final List<MarkedFarm> changes = new ArrayList<>();
    private final FarmRegistry registry = new FarmRegistry(FarmRegistry.DEFAULT_MAX_VOLUME, changes::add);

    @Test
    void everyMutationReportsTheNewFarmState() {
        var farm = registry.register(creator, mechanism(0), output(40));
        var profile = new ProductionProfile(Map.of(Material.KELP, 1.0), 3600);
        registry.markProfiling(farm.id());
        registry.markReady(farm.id(), profile);
        registry.markNeedsProfiling(farm.id());

        assertEquals(List.of(State.NEEDS_PROFILING, State.PROFILING, State.READY, State.NEEDS_PROFILING),
                changes.stream().map(MarkedFarm::state).toList());
        assertEquals(profile, changes.get(2).profile());
        assertTrue(changes.stream().allMatch(change -> change.id().equals(farm.id())));
    }

    @Test
    void restoredFarmKeepsIdentityCreatorAndProfile() {
        var profile = new ProductionProfile(Map.of(Material.KELP, 1.0), 3600);
        var saved = new MarkedFarm(UUID.randomUUID(), creator, mechanism(0), output(40), State.READY, profile);

        registry.restore(saved);

        assertEquals(saved, registry.requireCreator(saved.id(), creator));
        assertEquals(List.of(saved), registry.all());
        assertTrue(changes.isEmpty(), "unchanged restored farm needs no save");
    }

    @Test
    void unfinishedProfilingIsNotStitchedAcrossRestart() {
        var saved = new MarkedFarm(UUID.randomUUID(), creator, mechanism(0), output(40), State.PROFILING, null);

        var restored = registry.restore(saved);

        assertEquals(State.NEEDS_PROFILING, restored.state());
        assertEquals(List.of(restored), changes);
    }

    @Test
    void restoreAppliesTheSameRegistrationRules() {
        registry.restore(new MarkedFarm(UUID.randomUUID(), creator, mechanism(0), output(40),
                State.NEEDS_PROFILING, null));
        var overlapping = new MarkedFarm(UUID.randomUUID(), creator, mechanism(5), output(60),
                State.NEEDS_PROFILING, null);
        assertThrows(IllegalArgumentException.class, () -> registry.restore(overlapping));
        var duplicateId = new MarkedFarm(registry.all().getFirst().id(), creator, mechanism(100), output(140),
                State.NEEDS_PROFILING, null);
        assertThrows(IllegalArgumentException.class, () -> registry.restore(duplicateId));
        assertEquals(1, registry.all().size());
    }

    private static Cuboid mechanism(int x) {
        return new Cuboid(new BlockPosition(WORLD, x, 60, 0), new BlockPosition(WORLD, x + 4, 64, 4));
    }

    private static OutputContainer output(int x) {
        return new OutputContainer(OutputContainer.Type.CHEST, Set.of(new BlockPosition(WORLD, x, 60, 0)));
    }
}
