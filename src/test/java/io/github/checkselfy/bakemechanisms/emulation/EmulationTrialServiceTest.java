package io.github.checkselfy.bakemechanisms.emulation;

import static org.junit.jupiter.api.Assertions.*;

import io.github.checkselfy.bakemechanisms.marking.BlockPosition;
import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry;
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

class EmulationTrialServiceTest {
    private static final UUID WORLD = UUID.randomUUID();
    private final UUID creator = UUID.randomUUID();
    private final FarmRegistry registry = new FarmRegistry(FarmRegistry.DEFAULT_MAX_VOLUME);
    private final FakeWorld world = new FakeWorld();
    private final ChunkTickets tickets = new ChunkTickets(world);
    private final EmulationTrialService service =
            new EmulationTrialService(registry, world, tickets, EmulationSettings.DEFAULT);

    @Test
    void readyFarmDeliversItemsEveryIntervalWhileOutputChunkStaysLoaded() {
        var farm = ready(Map.of(Material.COBBLESTONE, 1.5), output(50));
        service.start(farm.id(), creator);
        assertEquals(Set.of(new ChunkPosition(WORLD, 3, 0)), world.tickets);
        assertEquals(State.READY, registry.requireCreator(farm.id(), creator).state());

        tick(19);
        assertTrue(world.items(farm.output()).isEmpty(), "output updates only on the interval");
        tick(1);
        assertEquals(Map.of(Material.COBBLESTONE, 1), world.items(farm.output()));
        tick(20);
        assertEquals(Map.of(Material.COBBLESTONE, 3), world.items(farm.output()));

        service.stop(farm.id(), creator);
        assertTrue(world.tickets.isEmpty());
        tick(200);
        assertEquals(Map.of(Material.COBBLESTONE, 3), world.items(farm.output()));
    }

    @Test
    void onlyCreatorCanRunOneTrialOfAReadyFarm() {
        var farm = ready(Map.of(Material.KELP, 1.0), output(50));
        var stranger = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> service.start(farm.id(), stranger));
        service.start(farm.id(), creator);
        var duplicate = assertThrows(IllegalArgumentException.class, () -> service.start(farm.id(), creator));
        assertTrue(duplicate.getMessage().contains("уже"));
        assertThrows(IllegalArgumentException.class, () -> service.stop(farm.id(), stranger));
        service.stop(farm.id(), creator);
        assertThrows(IllegalArgumentException.class, () -> service.stop(farm.id(), creator));

        registry.markNeedsProfiling(farm.id());
        var notReady = assertThrows(IllegalArgumentException.class, () -> service.start(farm.id(), creator));
        assertTrue(notReady.getMessage().contains("READY"));
        assertTrue(world.tickets.isEmpty());
    }

    @Test
    void updateIntervalIsConfigurable() {
        var sparse = new EmulationTrialService(registry, world, tickets, new EmulationSettings(100));
        var farm = ready(Map.of(Material.SAND, 0.1), output(50));
        sparse.start(farm.id(), creator);
        for (int i = 0; i < 99; i++) sparse.tick();
        assertTrue(world.items(farm.output()).isEmpty());
        sparse.tick();
        assertTrue(world.items(farm.output()).isEmpty(), "0.5 items stay in the fractional accumulator");
        for (int i = 0; i < 100; i++) sparse.tick();
        assertEquals(Map.of(Material.SAND, 1), world.items(farm.output()));
    }

    @Test
    void fullOutputKeepsBufferWhichStopReportsAsDropped() {
        var farm = ready(Map.of(Material.BONE, 5.0), output(50));
        world.capacity.put(farm.output(), 7);
        service.start(farm.id(), creator);
        tick(200);
        assertEquals(Map.of(Material.BONE, 7), world.items(farm.output()));
        assertEquals(Map.of(Material.BONE, 3), service.stop(farm.id(), creator));
    }

    @Test
    void lostOutputStopsTrialAndNotifiesCreator() {
        var farm = ready(Map.of(Material.KELP, 1.0), output(50));
        service.start(farm.id(), creator);
        world.outputs.clear();
        tick(20);
        assertTrue(world.tickets.isEmpty());
        assertTrue(world.messages.get(creator).stream().anyMatch(m -> m.contains("не найден")));
        assertThrows(IllegalArgumentException.class, () -> service.stop(farm.id(), creator));
        assertEquals(State.READY, registry.requireCreator(farm.id(), creator).state());
    }

    @Test
    void outputChunkTicketIsSharedWithOtherHoldersAndShutdownReleasesIt() {
        var farm = ready(Map.of(Material.KELP, 1.0), output(50));
        var outputChunk = new ChunkPosition(WORLD, 3, 0);
        tickets.acquire(outputChunk); // e.g. another farm being profiled next to this output
        service.start(farm.id(), creator);
        service.stopAll();
        assertEquals(Set.of(outputChunk), world.tickets);
        tickets.release(outputChunk);
        assertTrue(world.tickets.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.stop(farm.id(), creator));
    }

    private void tick(int ticks) {
        for (int i = 0; i < ticks; i++) service.tick();
    }

    private MarkedFarm ready(Map<Material, Double> rates, OutputContainer output) {
        var farm = registry.register(creator, new Cuboid(block(0, 0, 0), block(5, 5, 5)), output);
        world.outputs.put(output, new EnumMap<>(Material.class));
        return registry.markReady(farm.id(), new ProductionProfile(rates, 3600));
    }

    private static OutputContainer output(int x) {
        return new OutputContainer(OutputContainer.Type.CHEST, Set.of(block(x, 0, 0)));
    }

    private static BlockPosition block(int x, int y, int z) {
        return new BlockPosition(WORLD, x, y, z);
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
