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
        var farm = registry.register(creator, mechanism(0), output(5));
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
        var saved = new MarkedFarm(UUID.randomUUID(), creator, mechanism(0), output(5), State.READY, profile);

        registry.restore(saved);

        assertEquals(saved, registry.requireCreator(saved.id(), creator));
        assertEquals(List.of(saved), registry.all());
        assertTrue(changes.isEmpty(), "unchanged restored farm needs no save");
    }

    @Test
    void unfinishedProfilingIsNotStitchedAcrossRestart() {
        var saved = new MarkedFarm(UUID.randomUUID(), creator, mechanism(0), output(5), State.PROFILING, null);

        var restored = registry.restore(saved);

        assertEquals(State.NEEDS_PROFILING, restored.state());
        assertEquals(List.of(restored), changes);
    }

    @Test
    void restoreAppliesTheSameRegistrationRules() {
        registry.restore(new MarkedFarm(UUID.randomUUID(), creator, mechanism(0), output(5),
                State.NEEDS_PROFILING, null));
        var overlapping = new MarkedFarm(UUID.randomUUID(), creator, mechanism(5), output(60),
                State.NEEDS_PROFILING, null);
        assertThrows(IllegalArgumentException.class, () -> registry.restore(overlapping));
        var duplicateId = new MarkedFarm(registry.all().getFirst().id(), creator, mechanism(100), output(105),
                State.NEEDS_PROFILING, null);
        assertThrows(IllegalArgumentException.class, () -> registry.restore(duplicateId));
        assertEquals(1, registry.all().size());
    }

    @Test
    void changingAReadyMechanismDropsItsProfile() {
        var profile = new ProductionProfile(Map.of(Material.KELP, 1.0), 3600);
        var ready = registry.markReady(registry.register(creator, mechanism(0), output(5)).id(), profile);
        var untouched = registry.markReady(registry.register(creator, mechanism(100), output(105)).id(), profile);
        changes.clear();

        var invalidated = registry.invalidateReady(new BlockPosition(WORLD, 2, 62, 2));

        assertEquals(List.of(ready.id()), invalidated.stream().map(MarkedFarm::id).toList());
        var changed = registry.requireCreator(ready.id(), creator);
        assertEquals(State.NEEDS_PROFILING, changed.state());
        assertNull(changed.profile());
        assertEquals(List.of(changed), changes);
        assertEquals(untouched, registry.requireCreator(untouched.id(), creator));
    }

    @Test
    void onlyReadyFarmsAreInvalidatedByAChange() {
        var farm = registry.register(creator, mechanism(0), output(5));
        changes.clear();

        assertTrue(registry.invalidateReady(new BlockPosition(WORLD, 2, 62, 2)).isEmpty());
        registry.markProfiling(farm.id());
        assertTrue(registry.invalidateReady(new BlockPosition(WORLD, 2, 62, 2)).isEmpty());
        registry.markReady(farm.id(), new ProductionProfile(Map.of(Material.KELP, 1.0), 3600));
        assertTrue(registry.invalidateReady(new BlockPosition(WORLD, 5, 60, 0)).isEmpty(), "output is not the mechanism");
        assertEquals(State.READY, registry.requireCreator(farm.id(), creator).state());
    }

    @Test
    void removedFarmReleasesItsPlacement() {
        var farm = registry.register(creator, mechanism(0), output(5));

        registry.remove(farm.id());

        assertTrue(registry.all().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> registry.requireCreator(farm.id(), creator));
        assertDoesNotThrow(() -> registry.register(creator, mechanism(0), output(5)));
        assertThrows(IllegalArgumentException.class, () -> registry.remove(farm.id()));
    }

    @Test
    void farmMayHaveSeveralOutputsUpToTheLimitButNeverShareOneWithAnotherFarm() {
        var limited = new FarmRegistry(FarmRegistry.DEFAULT_MAX_VOLUME, 2, farm -> { });
        var farm = limited.register(creator, null, mechanism(0), List.of(output(5), output(5, 2)));
        assertEquals(List.of(output(5), output(5, 2)), farm.outputs());

        assertThrows(IllegalArgumentException.class,
                () -> limited.register(creator, null, mechanism(100), List.of(output(105), output(105, 2), output(105, 4))));
        assertThrows(IllegalArgumentException.class,
                () -> limited.register(creator, null, mechanism(100), List.of(output(105), output(105))));
        assertThrows(IllegalArgumentException.class, () -> limited.register(creator, null, mechanism(100),
                List.of(output(105), output(5, 2))), "the second output of the first farm is taken");
        assertThrows(IllegalArgumentException.class, () -> limited.register(creator, null, mechanism(100),
                List.of(output(105), output(102))), "an output inside the mechanism");
        assertThrows(IllegalArgumentException.class,
                () -> limited.register(creator, null, mechanism(40), List.of(output(105))),
                "a mechanism covering another farm's output");
    }

    @Test
    void namesAreOptionalCaseSensitiveAndUniqueOnlyAmongOneCreatorsFarms() {
        var other = UUID.randomUUID();
        var iron = registry.register(creator, "Iron", mechanism(0), List.of(output(5)));
        var unnamed = registry.register(creator, null, mechanism(100), List.of(output(105)));
        var lowercase = registry.register(creator, "iron", mechanism(200), List.of(output(205)));
        var foreign = registry.register(other, "Iron", mechanism(300), List.of(output(305)));

        assertThrows(IllegalArgumentException.class,
                () -> registry.register(creator, "Iron", mechanism(400), List.of(output(405))));
        assertEquals(iron, registry.require(new FarmArguments.Reference.ByName("Iron"), creator));
        assertEquals(lowercase, registry.require(new FarmArguments.Reference.ByName("iron"), creator));
        assertEquals(foreign, registry.require(new FarmArguments.Reference.ByName("Iron"), other));
        assertEquals(unnamed, registry.require(new FarmArguments.Reference.ById(unnamed.id()), creator));
        assertThrows(IllegalArgumentException.class,
                () -> registry.require(new FarmArguments.Reference.ByName("IRON"), creator));
        assertThrows(IllegalArgumentException.class,
                () -> registry.require(new FarmArguments.Reference.ById(foreign.id()), creator));
        for (var invalid : List.of("", "with space", "кириллица", "--id", "a".repeat(33))) {
            assertThrows(IllegalArgumentException.class,
                    () -> registry.register(creator, invalid, mechanism(400), List.of(output(405))), invalid);
        }
    }

    @Test
    void renameKeepsStateAndProfileAndFreesTheOldName() {
        var farm = registry.register(creator, "Kelp", mechanism(0), List.of(output(5)));
        var profile = new ProductionProfile(Map.of(Material.KELP, 1.0), 3600);
        registry.markReady(farm.id(), profile);
        var taken = registry.register(creator, "Taken", mechanism(100), List.of(output(105)));

        assertThrows(IllegalArgumentException.class, () -> registry.rename(farm.id(), creator, "Taken"));
        assertThrows(IllegalArgumentException.class, () -> registry.rename(farm.id(), UUID.randomUUID(), "Mine"));
        var renamed = registry.rename(farm.id(), creator, "Seaweed");

        assertEquals(new MarkedFarm(farm.id(), creator, "Seaweed", farm.mechanism(), farm.outputs(), State.READY,
                profile), renamed);
        assertEquals(renamed, changes.getLast(), "the new name is saved");
        assertEquals(taken, registry.rename(taken.id(), creator, "Taken"), "a farm may keep its own name");
        assertDoesNotThrow(() -> registry.rename(taken.id(), creator, "Kelp"));
    }

    @Test
    void stasisRecoveryKeepsTheNameThatTheSnapshotDoesNotHold() {
        var farm = registry.register(creator, "Iron", mechanism(0), List.of(output(5), output(5, 2)));
        var profile = new ProductionProfile(List.of(Map.of(Material.IRON_INGOT, 1.0), Map.of()), 3600);
        registry.recoverStasis(farm.withName(null).with(State.STASIS, profile));
        assertEquals(farm.with(State.STASIS, profile), registry.requireCreator(farm.id(), creator));
    }

    @Test
    void farmReferenceIsANameByDefaultAndAUuidOnlyAfterTheFlag() {
        var id = UUID.randomUUID();
        assertEquals(new FarmArguments(new FarmArguments.Reference.ByName("Iron"), List.of("confirm")),
                FarmArguments.parse(new String[] { "delete", "Iron", "confirm" }, 1));
        assertEquals(new FarmArguments(new FarmArguments.Reference.ByName(id.toString()), List.of()),
                FarmArguments.parse(new String[] { "info", id.toString() }, 1), "a bare UUID is read as a name");
        assertEquals(new FarmArguments(new FarmArguments.Reference.ById(id), List.of("confirm")),
                FarmArguments.parse(new String[] { "delete", "--id", id.toString(), "confirm" }, 1));
        assertThrows(IllegalArgumentException.class, () -> FarmArguments.parse(new String[] { "info", "--id" }, 1));
        assertThrows(IllegalArgumentException.class,
                () -> FarmArguments.parse(new String[] { "info", "--id", "Iron" }, 1));
        assertThrows(IllegalArgumentException.class, () -> FarmArguments.parse(new String[] { "info" }, 1));
    }

    private static Cuboid mechanism(int x) {
        return new Cuboid(new BlockPosition(WORLD, x, 60, 0), new BlockPosition(WORLD, x + 4, 64, 4));
    }

    private static OutputContainer output(int x) { return output(x, 0); }

    private static OutputContainer output(int x, int z) {
        return new OutputContainer(OutputContainer.Type.CHEST, Set.of(new BlockPosition(WORLD, x, 60, z)));
    }
}
