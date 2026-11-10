package io.github.checkselfy.bakemechanisms.marking;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.checkselfy.bakemechanisms.emulation.EmulationSettings;
import io.github.checkselfy.bakemechanisms.emulation.EmulationStore;
import io.github.checkselfy.bakemechanisms.emulation.EmulationWorld;
import io.github.checkselfy.bakemechanisms.emulation.StasisEmulationService;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.MarkedFarm;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.State;
import io.github.checkselfy.bakemechanisms.profiling.ChunkPosition;
import io.github.checkselfy.bakemechanisms.profiling.ChunkTickets;
import io.github.checkselfy.bakemechanisms.profiling.ProductionProfile;
import io.github.checkselfy.bakemechanisms.storage.FarmStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FarmRemovalTest {
    private static final UUID WORLD = UUID.randomUUID();
    private final UUID creator = UUID.randomUUID();
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final Set<ChunkPosition> loaded = new HashSet<>();
    private final ChunkTickets tickets = new ChunkTickets(new ChunkTickets.Backend() {
        @Override public void addChunkTicket(ChunkPosition chunk) { loaded.add(chunk); }

        @Override public void removeChunkTicket(ChunkPosition chunk) { loaded.remove(chunk); }
    });
    private final StasisEmulationService emulation = new StasisEmulationService(mock(EmulationWorld.class), tickets,
            EmulationSettings.DEFAULT, mock(EmulationStore.class));

    @TempDir
    Path root;

    private FarmStorage storage;
    private FarmRegistry registry;
    private FarmRemoval removal;

    private void setUp() {
        storage = new FarmStorage(root);
        registry = new FarmRegistry(FarmRegistry.DEFAULT_MAX_VOLUME, storage::save);
        removal = new FarmRemoval(registry, storage::delete, emulation::stopDraining, now::get);
    }

    @Test
    void confirmedRemovalDeletesTheRegistrationProfileAndSavedData() {
        setUp();
        var farm = ready();

        var requested = removal.request(farm.id(), creator);
        assertEquals(farm, requested);
        assertEquals(farm, registry.requireCreator(farm.id(), creator), "a request alone changes nothing");
        removal.confirm(farm.id(), creator);

        assertTrue(registry.all().isEmpty());
        assertFalse(Files.exists(root.resolve(farm.id().toString())));
        assertTrue(storage.loadAll().isEmpty());
        assertDoesNotThrow(() -> registry.register(creator, farm.mechanism(), farm.output()), "placement is free");
    }

    @Test
    void removalNeedsAnEarlierRequestByTheSamePlayerForTheSameFarm() {
        setUp();
        var farm = ready();
        var other = registry.register(creator, mechanism(100), output(140));

        assertThrows(IllegalArgumentException.class, () -> removal.confirm(farm.id(), creator));
        removal.request(other.id(), creator);
        assertThrows(IllegalArgumentException.class, () -> removal.confirm(farm.id(), creator));
        assertEquals(2, registry.all().size());
    }

    @Test
    void requestExpiresAndIsUsedOnlyOnce() {
        setUp();
        var farm = ready();
        removal.request(farm.id(), creator);
        now.addAndGet(FarmRemoval.CONFIRMATION_MILLIS + 1);

        assertThrows(IllegalArgumentException.class, () -> removal.confirm(farm.id(), creator));
        assertEquals(1, registry.all().size());

        removal.request(farm.id(), creator);
        removal.confirm(farm.id(), creator);
        assertThrows(IllegalArgumentException.class, () -> removal.confirm(farm.id(), creator));
    }

    @Test
    void onlyTheCreatorCanRemove() {
        setUp();
        var farm = ready();
        var stranger = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> removal.request(farm.id(), stranger));
        removal.request(farm.id(), creator);
        assertThrows(IllegalArgumentException.class, () -> removal.confirm(farm.id(), stranger));
        assertEquals(1, registry.all().size());
    }

    @Test
    void farmInStasisKeepsItsOnlySnapshot() throws Exception {
        setUp();
        var farm = registry.markStasis(ready().id());
        var directory = root.resolve(farm.id().toString());
        Files.write(directory.resolve("snapshot.bin"), new byte[] { 1 });
        Files.writeString(directory.resolve("stasis.yml"), "id: " + farm.id());

        assertThrows(IllegalArgumentException.class, () -> removal.request(farm.id(), creator));

        assertEquals(State.STASIS, registry.requireCreator(farm.id(), creator).state());
        assertTrue(Files.exists(directory.resolve("snapshot.bin")));
        assertTrue(Files.exists(directory.resolve("stasis.yml")));
    }

    @Test
    void farmEnteringStasisAfterTheRequestIsNotRemoved() throws Exception {
        setUp();
        var farm = ready();
        removal.request(farm.id(), creator);
        registry.markStasis(farm.id());
        Files.writeString(root.resolve(farm.id().toString()).resolve("stasis.yml"), "id: " + farm.id());

        assertThrows(IllegalArgumentException.class, () -> removal.confirm(farm.id(), creator));

        assertEquals(State.STASIS, registry.requireCreator(farm.id(), creator).state());
        assertTrue(Files.exists(root.resolve(farm.id().toString()).resolve("stasis.yml")));
    }

    @Test
    void profilingMustBeCancelledFirst() {
        setUp();
        var farm = registry.register(creator, mechanism(0), output(40));
        registry.markProfiling(farm.id());

        assertThrows(IllegalArgumentException.class, () -> removal.request(farm.id(), creator));
        assertEquals(State.PROFILING, registry.requireCreator(farm.id(), creator).state());
    }

    @Test
    void removalReleasesTheOutputChunkOfALeftoverBuffer() {
        setUp();
        var farm = registry.register(creator, mechanism(0), output(40));
        emulation.drain(farm, Map.of(Material.KELP, 3));
        assertFalse(loaded.isEmpty());

        removal.request(farm.id(), creator);
        removal.confirm(farm.id(), creator);

        assertTrue(loaded.isEmpty());
        assertFalse(emulation.draining(farm.id()));
    }

    @Test
    void failedDeletionKeepsTheRegistration() {
        storage = new FarmStorage(root);
        registry = new FarmRegistry(FarmRegistry.DEFAULT_MAX_VOLUME, storage::save);
        removal = new FarmRemoval(registry, id -> { throw new java.io.UncheckedIOException(
                new java.io.IOException("locked")); }, emulation::stopDraining, now::get);
        var farm = registry.register(creator, mechanism(0), output(40));
        emulation.drain(farm, Map.of(Material.KELP, 3));

        removal.request(farm.id(), creator);
        assertThrows(IllegalArgumentException.class, () -> removal.confirm(farm.id(), creator));

        assertEquals(List.of(farm), registry.all());
        assertTrue(emulation.draining(farm.id()));
    }

    private MarkedFarm ready() {
        var farm = registry.register(creator, mechanism(0), output(40));
        return registry.markReady(farm.id(), new ProductionProfile(Map.of(Material.KELP, 1.0), 3600));
    }

    private static Cuboid mechanism(int x) {
        return new Cuboid(new BlockPosition(WORLD, x, 60, 0), new BlockPosition(WORLD, x + 4, 64, 4));
    }

    private static OutputContainer output(int x) {
        return new OutputContainer(OutputContainer.Type.CHEST, Set.of(new BlockPosition(WORLD, x, 60, 0)));
    }
}
