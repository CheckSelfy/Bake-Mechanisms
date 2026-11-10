package io.github.checkselfy.bakemechanisms.profiling;

import static org.junit.jupiter.api.Assertions.*;

import io.github.checkselfy.bakemechanisms.marking.BlockPosition;
import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.State;
import io.github.checkselfy.bakemechanisms.marking.OutputContainer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class ProfilingServiceTest {
    private static final UUID WORLD = UUID.randomUUID();
    private final UUID creator = UUID.randomUUID();
    private final FarmRegistry registry = new FarmRegistry(FarmRegistry.DEFAULT_MAX_VOLUME);
    private final FakeWorld world = new FakeWorld();
    private final ProfilingService service = new ProfilingService(registry, world, ProfilingSettings.DEFAULT);

    @Test
    void completedRunStoresProfileAndReleasesAllTickets() {
        // Mechanism spans chunks (0..1, 0); output sits in chunk (3, 0).
        var farm = register(new Cuboid(block(0, 0, 0), block(20, 5, 5)), output(50));
        world.contents.put(farm.output(), new OutputSnapshot(Map.of(Material.COBBLESTONE, 7), false));
        service.start(farm.id(), creator);

        assertEquals(State.PROFILING, registry.requireCreator(farm.id(), creator).state());
        assertEquals(Set.of(chunk(0, 0), chunk(1, 0), chunk(3, 0)), world.tickets);

        world.contents.put(farm.output(), new OutputSnapshot(Map.of(Material.COBBLESTONE, 187), false));
        tick(ProfilingSettings.DURATION_TICKS - 1);
        assertEquals(State.PROFILING, registry.requireCreator(farm.id(), creator).state());
        tick(1);

        var done = registry.requireCreator(farm.id(), creator);
        assertEquals(State.READY, done.state());
        assertEquals(Map.of(Material.COBBLESTONE, 1.0), done.profile().ratesPer20Ticks());
        assertTrue(world.tickets.isEmpty());
        assertTrue(world.messages.getOrDefault(creator, List.of()).stream().anyMatch(m -> m.contains("READY")));
    }

    @Test
    void profilingWaitsUntilTheLeftoverBufferIsDelivered() {
        var farm = register(new Cuboid(block(0, 0, 0), block(5, 5, 5)), output(50));
        world.contents.put(farm.output(), new OutputSnapshot(Map.of(), false));
        var draining = new HashSet<UUID>(Set.of(farm.id()));
        var guarded = new ProfilingService(registry, world, ProfilingSettings.DEFAULT, new ChunkTickets(world),
                draining::contains);

        var error = assertThrows(IllegalArgumentException.class, () -> guarded.start(farm.id(), creator));
        assertTrue(error.getMessage().contains("буфер"), error.getMessage());
        assertEquals(State.NEEDS_PROFILING, registry.requireCreator(farm.id(), creator).state());
        assertTrue(world.tickets.isEmpty());

        draining.clear();
        guarded.start(farm.id(), creator);
        assertEquals(State.PROFILING, registry.requireCreator(farm.id(), creator).state());
    }

    @Test
    void creatorCancelReleasesTicketsAndAllowsRestart() {
        var farm = register(new Cuboid(block(0, 0, 0), block(5, 5, 5)), output(50));
        world.contents.put(farm.output(), new OutputSnapshot(Map.of(), false));
        var stranger = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> service.start(farm.id(), stranger));
        assertTrue(world.tickets.isEmpty());

        service.start(farm.id(), creator);
        var duplicate = assertThrows(IllegalArgumentException.class, () -> service.start(farm.id(), creator));
        assertTrue(duplicate.getMessage().contains("уже"));
        assertThrows(IllegalArgumentException.class, () -> service.cancel(farm.id(), stranger));
        tick(100);
        service.cancel(farm.id(), creator);

        assertEquals(State.NEEDS_PROFILING, registry.requireCreator(farm.id(), creator).state());
        assertTrue(world.tickets.isEmpty());
        int reads = world.reads;
        tick(ProfilingSettings.DURATION_TICKS);
        assertEquals(reads, world.reads, "cancelled session keeps sampling");
        assertEquals(State.NEEDS_PROFILING, registry.requireCreator(farm.id(), creator).state());
        assertThrows(IllegalArgumentException.class, () -> service.cancel(farm.id(), creator));

        service.start(farm.id(), creator);
        assertEquals(State.PROFILING, registry.requireCreator(farm.id(), creator).state());
    }

    @Test
    void concurrentFarmsKeepSharedOutputChunkLoadedUntilBothFinish() {
        var first = register(new Cuboid(block(0, 0, 0), block(5, 5, 5)), output(80));
        var second = register(new Cuboid(block(32, 0, 0), block(37, 5, 5)), output(82));
        world.contents.put(first.output(), new OutputSnapshot(Map.of(), false));
        world.contents.put(second.output(), new OutputSnapshot(Map.of(), false));
        service.start(first.id(), creator);
        tick(100);
        service.start(second.id(), creator);
        assertEquals(Set.of(chunk(0, 0), chunk(2, 0), chunk(5, 0)), world.tickets);

        world.contents.put(first.output(), new OutputSnapshot(Map.of(Material.KELP, 360), false));
        tick(ProfilingSettings.DURATION_TICKS - 100);
        assertEquals(State.READY, registry.requireCreator(first.id(), creator).state());
        assertEquals(State.PROFILING, registry.requireCreator(second.id(), creator).state());
        assertEquals(Set.of(chunk(2, 0), chunk(5, 0)), world.tickets);

        tick(100);
        assertEquals(Map.of(Material.KELP, 2.0),
                registry.requireCreator(first.id(), creator).profile().ratesPer20Ticks());
        assertEquals(State.NEEDS_PROFILING, registry.requireCreator(second.id(), creator).state());
        assertTrue(world.messages.get(creator).stream().anyMatch(m -> m.contains("ни одного предмета")));
        assertTrue(world.tickets.isEmpty());
    }

    @Test
    void startIsRejectedWithoutSideEffectsWhenPlayerInsideOrOutputMissing() {
        var farm = register(new Cuboid(block(0, 0, 0), block(5, 5, 5)), output(50));
        world.occupied.add(farm.mechanism());
        world.contents.put(farm.output(), new OutputSnapshot(Map.of(), false));
        var inside = assertThrows(IllegalArgumentException.class, () -> service.start(farm.id(), creator));
        assertTrue(inside.getMessage().contains("игрок"));

        world.occupied.clear();
        world.contents.clear();
        assertThrows(IllegalArgumentException.class, () -> service.start(farm.id(), creator));
        assertTrue(world.tickets.isEmpty());
        assertTrue(world.locked.isEmpty());
        assertEquals(State.NEEDS_PROFILING, registry.requireCreator(farm.id(), creator).state());
    }

    @Test
    void outputLostDuringRunAbortsProfiling() {
        var farm = register(new Cuboid(block(0, 0, 0), block(5, 5, 5)), output(50));
        world.contents.put(farm.output(), new OutputSnapshot(Map.of(), false));
        service.start(farm.id(), creator);
        world.contents.clear();
        tick(20);
        assertEquals(State.NEEDS_PROFILING, registry.requireCreator(farm.id(), creator).state());
        assertTrue(world.tickets.isEmpty());
        assertTrue(world.messages.get(creator).stream().anyMatch(m -> m.contains("не найден")));
    }

    @Test
    void shutdownCancelsEverySessionAndSaturatedOutputIsNoLongerSampled() {
        var farm = register(new Cuboid(block(0, 0, 0), block(5, 5, 5)), output(50));
        world.contents.put(farm.output(), new OutputSnapshot(Map.of(), false));
        service.start(farm.id(), creator);
        world.contents.put(farm.output(), new OutputSnapshot(Map.of(Material.STONE, 64), true));
        tick(20);
        int reads = world.reads;
        tick(1000);
        assertEquals(reads, world.reads);
        service.cancelAll();
        assertTrue(world.tickets.isEmpty());
        assertEquals(State.NEEDS_PROFILING, registry.requireCreator(farm.id(), creator).state());
    }

    @Test
    void snapshotIntervalIsConfigurableAndRunAlwaysEndsAt3600Ticks() {
        var sparse = new ProfilingService(registry, world, new ProfilingSettings(1000, 600, false));
        var farm = register(new Cuboid(block(0, 0, 0), block(5, 5, 5)), output(50));
        world.contents.put(farm.output(), new OutputSnapshot(Map.of(Material.SAND, 1), false));
        sparse.start(farm.id(), creator);
        world.contents.put(farm.output(), new OutputSnapshot(Map.of(Material.SAND, 181), false));
        for (int i = 0; i < ProfilingSettings.DURATION_TICKS; i++) sparse.tick();
        assertEquals(1 + 4, world.reads); // baseline + 1000, 2000, 3000, 3600
        assertEquals(new ProductionProfile(Map.of(Material.SAND, 1.0), 3600),
                registry.requireCreator(farm.id(), creator).profile());
    }

    @Test
    void onlyFarmsNeedingProfilingCanStartAndFailedTicketsDoNotLeak() {
        var farm = register(new Cuboid(block(0, 0, 0), block(20, 5, 5)), output(50));
        world.contents.put(farm.output(), new OutputSnapshot(Map.of(), false));
        world.failingTicket = chunk(1, 0);
        assertThrows(IllegalArgumentException.class, () -> service.start(farm.id(), creator));
        assertTrue(world.tickets.isEmpty());
        assertEquals(State.NEEDS_PROFILING, registry.requireCreator(farm.id(), creator).state());

        world.failingTicket = null;
        registry.markReady(farm.id(), new ProductionProfile(Map.of(Material.DIRT, 1.0), 20));
        var ready = assertThrows(IllegalArgumentException.class, () -> service.start(farm.id(), creator));
        assertTrue(ready.getMessage().contains("NEEDS_PROFILING"));
        assertTrue(world.tickets.isEmpty());
    }

    private void tick(int ticks) {
        for (int i = 0; i < ticks; i++) service.tick();
    }

    private FarmRegistry.MarkedFarm register(Cuboid mechanism, OutputContainer output) {
        return registry.register(creator, mechanism, output);
    }

    private static OutputContainer output(int x) {
        return new OutputContainer(OutputContainer.Type.CHEST, Set.of(block(x, 0, 0)));
    }

    private static BlockPosition block(int x, int y, int z) {
        return new BlockPosition(WORLD, x, y, z);
    }

    private static ChunkPosition chunk(int x, int z) {
        return new ChunkPosition(WORLD, x, z);
    }

    /** In-memory world: tracks plugin tickets as a set, like Paper does for one plugin. */
    static final class FakeWorld implements ProfilingWorld {
        final Map<OutputContainer, OutputSnapshot> contents = new HashMap<>();
        final Set<ChunkPosition> tickets = new HashSet<>();
        final Set<Cuboid> occupied = new HashSet<>();
        final Map<UUID, List<String>> messages = new HashMap<>();
        final List<OutputContainer> locked = new ArrayList<>();
        int reads;
        ChunkPosition failingTicket;

        @Override
        public OutputSnapshot readOutput(OutputContainer output) {
            reads++;
            var snapshot = contents.get(output);
            if (snapshot == null) throw new IllegalStateException("Выходной контейнер не найден.");
            return snapshot;
        }

        @Override
        public boolean hasPlayerInside(Cuboid mechanism) {
            return occupied.contains(mechanism);
        }

        @Override
        public void lockOutput(OutputContainer output) {
            locked.add(output);
        }

        @Override
        public void addChunkTicket(ChunkPosition chunk) {
            if (chunk.equals(failingTicket)) throw new IllegalArgumentException("Мир фермы не загружен.");
            assertTrue(tickets.add(chunk), "duplicate ticket " + chunk);
        }

        @Override
        public void removeChunkTicket(ChunkPosition chunk) {
            assertTrue(tickets.remove(chunk), "missing ticket " + chunk);
        }

        @Override
        public void notify(UUID playerId, String message) {
            messages.computeIfAbsent(playerId, id -> new ArrayList<>()).add(message);
        }
    }
}
