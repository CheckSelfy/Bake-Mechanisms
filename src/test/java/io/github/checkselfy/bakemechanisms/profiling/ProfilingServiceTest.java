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
        world.contents.put(farm.outputs().get(0), new OutputSnapshot(Map.of(Material.COBBLESTONE, 7), false));
        service.start(farm.id(), creator);

        assertEquals(State.PROFILING, registry.requireCreator(farm.id(), creator).state());
        assertEquals(Set.of(chunk(0, 0), chunk(1, 0), chunk(3, 0)), world.tickets);

        world.produce(farm.outputs().get(0), Material.COBBLESTONE, 20);
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
        world.contents.put(farm.outputs().get(0), new OutputSnapshot(Map.of(), false));
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
        world.contents.put(farm.outputs().get(0), new OutputSnapshot(Map.of(), false));
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
        world.contents.put(first.outputs().get(0), new OutputSnapshot(Map.of(), false));
        world.contents.put(second.outputs().get(0), new OutputSnapshot(Map.of(), false));
        service.start(first.id(), creator);
        tick(100);
        service.start(second.id(), creator);
        assertEquals(Set.of(chunk(0, 0), chunk(2, 0), chunk(5, 0)), world.tickets);

        world.produce(first.outputs().get(0), Material.KELP, 10);
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
        world.contents.put(farm.outputs().get(0), new OutputSnapshot(Map.of(), false));
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
        world.contents.put(farm.outputs().get(0), new OutputSnapshot(Map.of(), false));
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
        world.contents.put(farm.outputs().get(0), new OutputSnapshot(Map.of(), false));
        service.start(farm.id(), creator);
        world.contents.put(farm.outputs().get(0), new OutputSnapshot(Map.of(Material.STONE, 64), true));
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
        world.contents.put(farm.outputs().get(0), new OutputSnapshot(Map.of(Material.SAND, 1), false));
        sparse.start(farm.id(), creator);
        world.produce(farm.outputs().get(0), Material.SAND, 20);
        for (int i = 0; i < ProfilingSettings.DURATION_TICKS; i++) {
            world.advance();
            sparse.tick();
        }
        assertEquals(1 + 4, world.reads); // baseline + 1000, 2000, 3000, 3600
        // Measured from the first snapshot with new items (1000) to the end.
        assertEquals(new ProductionProfile(Map.of(Material.SAND, 1.0), 2600),
                registry.requireCreator(farm.id(), creator).profile());
    }

    @Test
    void onlyFarmsNeedingProfilingCanStartAndFailedTicketsDoNotLeak() {
        var farm = register(new Cuboid(block(0, 0, 0), block(20, 5, 5)), output(50));
        world.contents.put(farm.outputs().get(0), new OutputSnapshot(Map.of(), false));
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

    @Test
    void everyOutputIsProtectedAndMeasuredAndTheCreatorLearnsWhenOneFills() {
        var first = output(50);
        var second = output(80); // chunk (5, 0)
        var farm = registry.register(creator, "Mobs", new Cuboid(block(0, 0, 0), block(5, 5, 5)),
                List.of(first, second));
        world.contents.put(first, new OutputSnapshot(Map.of(), false));
        world.contents.put(second, new OutputSnapshot(Map.of(Material.BONE, 3), false));
        service.start(farm.id(), creator);
        assertEquals(List.of(first, second), world.locked);
        assertEquals(Set.of(chunk(0, 0), chunk(3, 0), chunk(5, 0)), world.tickets);

        world.produce(first, Material.ARROW, 20);
        world.produce(second, Material.BONE, 10);
        tick(200);
        world.full.add(second);
        tick(20);
        var messages = world.messages.get(creator);
        assertEquals(1, messages.size());
        assertTrue(messages.getFirst().contains("Mobs") && messages.getFirst().contains("№2")
                && messages.getFirst().contains("220"), messages.getFirst());

        tick(ProfilingSettings.DURATION_TICKS - 220);
        assertEquals(1, world.messages.get(creator).stream().filter(message -> message.contains("заполнился"))
                .count(), "reported once");
        var profile = registry.requireCreator(farm.id(), creator).profile();
        assertEquals(180, profile.usefulTicks(), "from the first items at tick 20 to the last snapshot before 220");
        assertEquals(List.of(Map.of(Material.ARROW, 1.0), Map.of(Material.BONE, 2.0)), profile.outputs());
    }

    @Test
    void outputFullBeforeProfilingStartsIsReportedRightAway() {
        var farm = register(new Cuboid(block(0, 0, 0), block(5, 5, 5)), output(50));
        world.contents.put(farm.outputs().get(0), new OutputSnapshot(Map.of(Material.DIRT, 1728), true));
        service.start(farm.id(), creator);
        var messages = world.messages.get(creator);
        assertEquals(1, messages.size());
        assertTrue(messages.getFirst().contains("№1") && messages.getFirst().contains("тике 0"), messages.getFirst());
    }

    @Test
    void remainingTicksCountDownToTheEndOfProfilingForTheCreatorOnly() {
        var farm = register(new Cuboid(block(0, 0, 0), block(5, 5, 5)), output(50));
        world.contents.put(farm.outputs().get(0), new OutputSnapshot(Map.of(), false));
        assertThrows(IllegalArgumentException.class, () -> service.remainingTicks(farm.id(), creator),
                "not profiling yet");
        service.start(farm.id(), creator);
        assertEquals(ProfilingSettings.DURATION_TICKS, service.remainingTicks(farm.id(), creator));
        tick(1000);
        assertEquals(ProfilingSettings.DURATION_TICKS - 1000, service.remainingTicks(farm.id(), creator));
        assertThrows(IllegalArgumentException.class, () -> service.remainingTicks(farm.id(), UUID.randomUUID()));
        tick((int) service.remainingTicks(farm.id(), creator));
        assertNotEquals(State.PROFILING, registry.requireCreator(farm.id(), creator).state(),
                "sprinting the remaining ticks finishes profiling");
    }

    @Test
    void farmSwitchedOnLongAfterProfilingStartedGetsItsFullRate() {
        var farm = register(new Cuboid(block(0, 0, 0), block(5, 5, 5)), output(50));
        world.contents.put(farm.outputs().get(0), new OutputSnapshot(Map.of(), false));
        service.start(farm.id(), creator);
        tick(1500); // the creator has not switched the farm on yet
        world.produce(farm.outputs().get(0), Material.STRING, 20);
        tick(ProfilingSettings.DURATION_TICKS - 1500);
        var profile = registry.requireCreator(farm.id(), creator).profile();
        assertEquals(Map.of(Material.STRING, 1.0), profile.ratesPer20Ticks());
    }

    @Test
    void progressEventsFollowServerTicksAndSessionLifecycle() {
        var events = new ArrayList<String>();
        var listener = new ProfilingService.ProgressListener() {
            @Override public void started(FarmRegistry.MarkedFarm farm) {
                events.add("start:" + farm.id());
            }

            @Override public void progressed(FarmRegistry.MarkedFarm farm, long elapsedTicks) {
                events.add("tick:" + elapsedTicks);
            }

            @Override public void ended(UUID farmId) {
                events.add("end:" + farmId);
            }
        };
        var observed = new ProfilingService(registry, world, ProfilingSettings.DEFAULT, new ChunkTickets(world),
                farmId -> false, listener);
        var farm = register(new Cuboid(block(0, 0, 0), block(5, 5, 5)), output(50));
        world.contents.put(farm.outputs().get(0), new OutputSnapshot(Map.of(), false));

        observed.start(farm.id(), creator);
        observed.tick();
        observed.tick();
        observed.cancel(farm.id(), creator);

        assertEquals(List.of("start:" + farm.id(), "tick:1", "tick:2", "end:" + farm.id()), events);
        assertEquals(1, world.reads, "progress callbacks do not sample the output");
    }

    private void tick(int ticks) {
        for (int i = 0; i < ticks; i++) {
            world.advance();
            service.tick();
        }
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
        /** Outputs that receive one item of a type every given number of ticks. */
        final Map<OutputContainer, Map.Entry<Material, Integer>> production = new HashMap<>();
        final Set<OutputContainer> full = new HashSet<>();
        int reads;
        long time;
        ChunkPosition failingTicket;

        void produce(OutputContainer output, Material type, int everyTicks) {
            production.put(output, Map.entry(type, everyTicks));
        }

        /** One server tick of the simulated farms. */
        void advance() {
            time++;
            production.forEach((output, item) -> {
                if (time % item.getValue() != 0) return;
                var counts = new HashMap<>(contents.get(output).counts());
                counts.merge(item.getKey(), 1, Integer::sum);
                contents.put(output, new OutputSnapshot(counts, false));
            });
        }

        @Override
        public OutputSnapshot readOutput(OutputContainer output) {
            reads++;
            var snapshot = contents.get(output);
            if (snapshot == null) throw new IllegalStateException("Выходной контейнер не найден.");
            return full.contains(output) ? new OutputSnapshot(snapshot.counts(), true) : snapshot;
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
