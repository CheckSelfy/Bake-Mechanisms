package io.github.checkselfy.bakemechanisms.emulation;

import static org.junit.jupiter.api.Assertions.*;

import io.github.checkselfy.bakemechanisms.marking.BlockPosition;
import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.MarkedFarm;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.State;
import io.github.checkselfy.bakemechanisms.marking.OutputContainer;
import io.github.checkselfy.bakemechanisms.profiling.ChunkPosition;
import io.github.checkselfy.bakemechanisms.profiling.ChunkTickets;
import io.github.checkselfy.bakemechanisms.profiling.ProductionProfile;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class StasisEmulationServiceTest {
    private static final UUID WORLD = UUID.randomUUID();
    private static final ChunkPosition OUTPUT_CHUNK = new ChunkPosition(WORLD, 3, 0);
    private final UUID creator = UUID.randomUUID();
    private final FakeWorld world = new FakeWorld();
    private final FakeStore store = new FakeStore();
    private final ChunkTickets tickets = new ChunkTickets(world);
    private final StasisEmulationService service =
            new StasisEmulationService(world, tickets, EmulationSettings.DEFAULT, store);

    @Test
    void stasisFarmProducesIntoOutputEveryIntervalWhileOutputChunkStaysLoaded() {
        var farm = stasis(Map.of(Material.COBBLESTONE, 1.5));
        service.start(farm, empty());
        assertEquals(Set.of(OUTPUT_CHUNK), world.tickets);

        tick(19);
        assertTrue(world.items(farm.outputs().get(0)).isEmpty(), "output updates only on the interval");
        tick(1);
        assertEquals(Map.of(Material.COBBLESTONE, 1), world.items(farm.outputs().get(0)));
        tick(20);
        assertEquals(Map.of(Material.COBBLESTONE, 3), world.items(farm.outputs().get(0)));
    }

    @Test
    void progressIsSavedEachUpdateAndARestartContinuesWithoutLossOrDuplication() {
        var farm = stasis(Map.of(Material.KELP, 0.25));
        service.start(farm, empty());
        tick(60); // 0.75 kelp, still fractional
        assertTrue(world.items(farm.outputs().get(0)).isEmpty());
        var saved = store.emulation.get(farm.id());
        assertEquals(0, saved.outputs().get(0).fractions().get(Material.KELP).compareTo(new java.math.BigDecimal("0.75")));
        service.stopAll();
        assertTrue(world.tickets.isEmpty(), "shutdown releases the output chunk");

        var restarted = new StasisEmulationService(world, tickets, EmulationSettings.DEFAULT, store);
        restarted.start(farm, saved);
        for (int i = 0; i < 20; i++) restarted.tick();
        assertEquals(Map.of(Material.KELP, 1), world.items(farm.outputs().get(0)));
    }

    @Test
    void secondStartOfAProducingFarmIsRejectedWithoutDroppingItsBufferOrLeakingTickets() {
        var farm = stasis(Map.of(Material.KELP, 1.0));
        world.capacity.put(farm.outputs().get(0), 0);
        service.start(farm, empty());
        tick(20); // one kelp waits in the buffer
        var before = store.emulation.get(farm.id());
        assertThrows(IllegalStateException.class, () -> service.start(farm, empty()));
        assertTrue(service.producing(farm.id()));
        service.stopAll();
        assertEquals(before, store.emulation.get(farm.id()), "the running stream and its buffer are kept");
        assertTrue(world.tickets.isEmpty(), "one stream holds one ticket per output chunk");
    }

    @Test
    void oneStreamProducesExactlyTheProfileNoMatterHowOftenOtherFarmsStartAndFinish() {
        var farm = stasis(Map.of(Material.KELP, 1.0));
        service.start(farm, empty());
        for (int cycle = 0; cycle < 10; cycle++) {
            var otherOutput = new OutputContainer(OutputContainer.Type.BARREL, Set.of(block(60 + cycle, 0, 0)));
            world.outputs.put(otherOutput, new EnumMap<>(Material.class));
            var other = new MarkedFarm(UUID.randomUUID(), creator, new Cuboid(block(10, 0, 0), block(12, 2, 2)),
                    otherOutput, State.STASIS, new ProductionProfile(Map.of(Material.BONE, 1.0), 3600));
            service.start(other, empty());
            tick(20);
            service.finish(other.id());
        }
        assertEquals(Map.of(Material.KELP, 10), world.items(farm.outputs().get(0)));
    }

    @Test
    void idleUpdatesDoNotRewriteUnchangedState() {
        var farm = stasis(Map.of(Material.KELP, 1.0));
        world.capacity.put(farm.outputs().get(0), 0);
        service.start(farm, empty());
        tick(20); // one kelp waits in the buffer
        int writes = store.writes;
        tick(200);
        assertEquals(writes, store.writes);
        assertEquals(Map.of(Material.KELP, 1), store.emulation.get(farm.id()).outputs().get(0).buffer());
    }

    @Test
    void pausedFarmProducesNothingAndResumeContinuesProduction() {
        var farm = stasis(Map.of(Material.KELP, 1.0));
        service.start(farm, empty());
        service.pause(farm.id());
        tick(100);
        assertTrue(world.items(farm.outputs().get(0)).isEmpty(), "no production while the original is being restored");
        service.resume(farm.id()); // restore failed: the filler is back
        tick(20);
        assertEquals(Map.of(Material.KELP, 1), world.items(farm.outputs().get(0)));
    }

    @Test
    void restoredFarmStopsProducingButDeliversLeftoverBufferAsSpaceAppears() {
        var farm = stasis(Map.of(Material.BONE, 5.0));
        world.capacity.put(farm.outputs().get(0), 7);
        service.start(farm, empty());
        tick(40); // 10 bones produced, 7 fit, 3 buffered
        service.pause(farm.id());
        service.finish(farm.id());
        assertTrue(service.draining(farm.id()));
        assertEquals(List.of(Map.of(Material.BONE, 3)), store.drains.get(farm.id()));
        assertEquals(Set.of(OUTPUT_CHUNK), world.tickets, "output stays loaded while the buffer drains");

        world.outputs.get(farm.outputs().get(0)).put(Material.BONE, 5); // a hopper took 2 bones
        tick(20);
        assertEquals(Map.of(Material.BONE, 7), world.items(farm.outputs().get(0)));
        assertEquals(List.of(Map.of(Material.BONE, 1)), store.drains.get(farm.id()));

        world.outputs.get(farm.outputs().get(0)).clear();
        tick(200);
        assertEquals(Map.of(Material.BONE, 1), world.items(farm.outputs().get(0)), "no new production after restore");
        assertFalse(service.draining(farm.id()));
        assertFalse(store.drains.containsKey(farm.id()));
        assertTrue(world.tickets.isEmpty());
    }

    @Test
    void finishWithEmptyBufferEndsImmediately() {
        var farm = stasis(Map.of(Material.KELP, 0.1));
        service.start(farm, empty());
        tick(20);
        service.finish(farm.id());
        assertFalse(service.draining(farm.id()));
        assertFalse(store.drains.containsKey(farm.id()));
        assertTrue(world.tickets.isEmpty());
    }

    @Test
    void lostOutputKeepsProgressRetriesAndNotifiesCreatorOnce() {
        var farm = stasis(Map.of(Material.KELP, 1.0));
        service.start(farm, empty());
        tick(20);
        var items = world.outputs.remove(farm.outputs().get(0));
        tick(100);
        assertEquals(1, world.messages.get(creator).size());
        assertTrue(world.messages.get(creator).getFirst().contains("сохранён"));
        assertEquals(Set.of(OUTPUT_CHUNK), world.tickets);

        world.outputs.put(farm.outputs().get(0), items); // container rebuilt
        tick(20);
        // One kelp waited in the buffer during the loss (production paused), plus this interval's kelp.
        assertEquals(Map.of(Material.KELP, 3), world.items(farm.outputs().get(0)));
        world.outputs.remove(farm.outputs().get(0));
        tick(20);
        assertEquals(2, world.messages.get(creator).size(), "a new loss is reported again");
    }

    @Test
    void lostOutputDuringDrainKeepsLeftoverBuffer() {
        var farm = stasis(Map.of(Material.BONE, 5.0));
        world.capacity.put(farm.outputs().get(0), 0);
        service.start(farm, empty());
        tick(20);
        service.finish(farm.id());
        var items = world.outputs.remove(farm.outputs().get(0));
        tick(100);
        assertTrue(service.draining(farm.id()));
        assertEquals(List.of(Map.of(Material.BONE, 5)), store.drains.get(farm.id()));
        assertEquals(1, world.messages.get(creator).stream().filter(message -> message.contains("недоступен"))
                .count(), "the loss is reported once");
        world.outputs.put(farm.outputs().get(0), items);
        world.capacity.remove(farm.outputs().get(0));
        tick(20);
        assertEquals(Map.of(Material.BONE, 5), world.items(farm.outputs().get(0)));
        assertFalse(service.draining(farm.id()));
    }

    @Test
    void leftoverBufferContinuesDrainingAfterRestart() {
        var farm = stasis(Map.of(Material.BONE, 5.0));
        var restored = new MarkedFarm(farm.id(), creator, farm.mechanism(), farm.outputs().get(0), State.NEEDS_PROFILING,
                null);
        service.drain(restored, List.of(Map.of(Material.BONE, 4)));
        assertTrue(service.draining(farm.id()));
        assertEquals(Set.of(OUTPUT_CHUNK), world.tickets);
        tick(20);
        assertEquals(Map.of(Material.BONE, 4), world.items(farm.outputs().get(0)));
        assertFalse(service.draining(farm.id()));
        assertTrue(world.tickets.isEmpty());
    }

    @Test
    void shutdownSavesLeftoverBufferAgainSoAFailedWriteLosesNothing() {
        var farm = stasis(Map.of(Material.BONE, 5.0));
        world.capacity.put(farm.outputs().get(0), 0);
        service.start(farm, empty());
        tick(20);
        service.finish(farm.id());
        store.drains.clear(); // the write in finish failed
        service.stopAll();
        assertEquals(List.of(Map.of(Material.BONE, 5)), store.drains.get(farm.id()));
        assertTrue(world.tickets.isEmpty());
    }

    @Test
    void oneFullOutputPausesAllOutputsWithOneMessageAndProductionResumesOnceEveryBufferIsDelivered() {
        var kelp = new OutputContainer(OutputContainer.Type.CHEST, Set.of(block(50, 0, 0)));
        var bamboo = new OutputContainer(OutputContainer.Type.BARREL, Set.of(block(52, 0, 0)));
        world.outputs.put(kelp, new EnumMap<>(Material.class));
        world.outputs.put(bamboo, new EnumMap<>(Material.class));
        var farm = new MarkedFarm(UUID.randomUUID(), creator, "Swamp", new Cuboid(block(0, 0, 0), block(5, 5, 5)),
                List.of(kelp, bamboo), State.STASIS, new ProductionProfile(
                        List.of(Map.of(Material.KELP, 2.0), Map.of(Material.BAMBOO, 1.0)), 3600));
        world.capacity.put(bamboo, 1);
        service.start(farm, empty());

        tick(20);
        assertEquals(List.of(), world.messages.getOrDefault(creator, List.of()), "both outputs accepted everything");
        tick(20); // bamboo does not fit: production of both outputs stops
        tick(100);
        assertEquals(Map.of(Material.KELP, 4), world.items(kelp));
        assertEquals(Map.of(Material.BAMBOO, 1), world.items(bamboo));
        var paused = world.messages.get(creator);
        assertEquals(1, paused.size(), "the creator is told once per pause");
        assertTrue(paused.getFirst().contains("Swamp") && paused.getFirst().contains("№2")
                && paused.getFirst().contains("приостановлено"), paused.getFirst());

        world.capacity.remove(bamboo);
        tick(20); // the buffered bamboo is delivered and both outputs produce again
        assertEquals(Map.of(Material.KELP, 6), world.items(kelp));
        assertEquals(Map.of(Material.BAMBOO, 3), world.items(bamboo));
        assertEquals(2, world.messages.get(creator).size());
        assertTrue(world.messages.get(creator).get(1).contains("возобновлено"), world.messages.get(creator).get(1));
    }

    @Test
    void leftoverBufferOfEveryOutputIsDeliveredToThatOutput() {
        var first = new OutputContainer(OutputContainer.Type.CHEST, Set.of(block(50, 0, 0)));
        var second = new OutputContainer(OutputContainer.Type.CHEST, Set.of(block(52, 0, 0)));
        world.outputs.put(first, new EnumMap<>(Material.class));
        world.outputs.put(second, new EnumMap<>(Material.class));
        var farm = new MarkedFarm(UUID.randomUUID(), creator, null, new Cuboid(block(0, 0, 0), block(5, 5, 5)),
                List.of(first, second), State.NEEDS_PROFILING, null);
        world.capacity.put(second, 2);
        service.drain(farm, List.of(Map.of(Material.BONE, 3), Map.of(Material.ARROW, 5)));

        tick(20);
        assertEquals(Map.of(Material.BONE, 3), world.items(first));
        assertEquals(Map.of(Material.ARROW, 2), world.items(second));
        assertEquals(List.of(Map.of(), Map.of(Material.ARROW, 3)), store.drains.get(farm.id()));
        world.capacity.remove(second);
        tick(20);
        assertEquals(Map.of(Material.ARROW, 5), world.items(second));
        assertFalse(service.draining(farm.id()));
    }

    private void tick(int ticks) {
        for (int i = 0; i < ticks; i++) service.tick();
    }

    private static EmulationState empty() {
        return EmulationState.EMPTY;
    }

    private MarkedFarm stasis(Map<Material, Double> rates) {
        var output = new OutputContainer(OutputContainer.Type.CHEST, Set.of(block(50, 0, 0)));
        world.outputs.put(output, new EnumMap<>(Material.class));
        return new MarkedFarm(UUID.randomUUID(), creator, new Cuboid(block(0, 0, 0), block(5, 5, 5)), output,
                State.STASIS, new ProductionProfile(rates, 3600));
    }

    private static BlockPosition block(int x, int y, int z) {
        return new BlockPosition(WORLD, x, y, z);
    }

    static final class FakeStore implements EmulationStore {
        final Map<UUID, EmulationState> emulation = new HashMap<>();
        final Map<UUID, List<Map<Material, Integer>>> drains = new HashMap<>();
        int writes;

        @Override
        public void saveEmulation(UUID farmId, EmulationState state) {
            writes++;
            emulation.put(farmId, state);
        }

        @Override
        public void saveDrain(UUID farmId, List<Map<Material, Integer>> buffers) {
            writes++;
            drains.put(farmId, buffers.stream().map(Map::copyOf).toList());
        }

        @Override
        public void deleteDrain(UUID farmId) {
            drains.remove(farmId);
        }
    }

    static final class FakeWorld implements EmulationWorld, ChunkTickets.Backend {
        final Map<OutputContainer, Map<Material, Integer>> outputs = new HashMap<>();
        final Map<OutputContainer, Integer> capacity = new HashMap<>();
        final Set<ChunkPosition> tickets = new HashSet<>();
        final Map<UUID, List<String>> messages = new HashMap<>();

        Map<Material, Integer> items(OutputContainer output) {
            return Map.copyOf(outputs.get(output));
        }

        @Override
        public int insert(OutputContainer output, Material type, int amount) {
            var items = outputs.get(output);
            if (items == null) throw new IllegalStateException("Выходной контейнер не найден.");
            int stored = items.values().stream().mapToInt(Integer::intValue).sum();
            int accepted = Math.min(amount, capacity.getOrDefault(output, Integer.MAX_VALUE) - stored);
            if (accepted > 0) items.merge(type, accepted, Integer::sum);
            return Math.max(accepted, 0);
        }

        @Override
        public void notify(UUID playerId, String message) {
            messages.computeIfAbsent(playerId, id -> new ArrayList<>()).add(message);
        }

        @Override
        public void addChunkTicket(ChunkPosition chunk) {
            assertTrue(tickets.add(chunk), "duplicate ticket " + chunk);
        }

        @Override
        public void removeChunkTicket(ChunkPosition chunk) {
            assertTrue(tickets.remove(chunk), "missing ticket " + chunk);
        }
    }
}
