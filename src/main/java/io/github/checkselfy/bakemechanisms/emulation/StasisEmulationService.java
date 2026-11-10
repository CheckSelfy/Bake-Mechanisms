package io.github.checkselfy.bakemechanisms.emulation;

import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.MarkedFarm;
import io.github.checkselfy.bakemechanisms.profiling.ChunkPosition;
import io.github.checkselfy.bakemechanisms.profiling.ChunkTickets;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;

/**
 * Produces items for farms in stasis and, after a restore, delivers their leftover buffer. The output chunk
 * stays loaded for both. {@link #tick()} must be called once per server tick; all methods run on the server
 * thread.
 */
public final class StasisEmulationService {
    private final EmulationWorld world;
    private final ChunkTickets tickets;
    private final EmulationSettings settings;
    private final EmulationStore store;
    private final Map<UUID, Running> running = new LinkedHashMap<>();
    private final Map<UUID, Draining> draining = new LinkedHashMap<>();

    private abstract static class Entry {
        final MarkedFarm farm;
        final Set<ChunkPosition> chunks;
        long elapsed;
        /** The creator was told the output is unavailable; reset by the next successful delivery. */
        boolean reportedLoss;

        Entry(MarkedFarm farm, Set<ChunkPosition> chunks) {
            this.farm = farm;
            this.chunks = chunks;
        }

        ProfileEmulation.Output output(EmulationWorld world) {
            return (type, amount) -> world.insert(farm.output(), type, amount);
        }
    }

    private static final class Running extends Entry {
        final ProfileEmulation emulation;
        boolean paused;

        Running(MarkedFarm farm, Set<ChunkPosition> chunks, ProfileEmulation emulation) {
            super(farm, chunks);
            this.emulation = emulation;
        }
    }

    private static final class Draining extends Entry {
        final Map<Material, Integer> buffer;

        Draining(MarkedFarm farm, Set<ChunkPosition> chunks, Map<Material, Integer> buffer) {
            super(farm, chunks);
            this.buffer = new EnumMap<>(Material.class);
            this.buffer.putAll(buffer);
        }
    }

    public StasisEmulationService(EmulationWorld world, ChunkTickets tickets, EmulationSettings settings,
                                  EmulationStore store) {
        this.world = Objects.requireNonNull(world, "world");
        this.tickets = Objects.requireNonNull(tickets, "tickets");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.store = Objects.requireNonNull(store, "store");
    }

    /** Starts or continues production of a farm in stasis from {@code state}, keeping its output loaded. */
    public void start(MarkedFarm farm, EmulationState state) {
        var emulation = new ProfileEmulation(farm.profile(), state);
        var chunks = outputChunks(farm);
        tickets.acquireAll(chunks);
        running.put(farm.id(), new Running(farm, chunks, emulation));
    }

    /** Stops production while the original mechanism is being placed; state and tickets are kept. */
    public void pause(UUID farmId) {
        var farm = running.get(farmId);
        if (farm != null) farm.paused = true;
    }

    /** Restore failed and the filler is back: production continues from where it paused. */
    public void resume(UUID farmId) {
        var farm = running.get(farmId);
        if (farm != null) farm.paused = false;
    }

    /**
     * The original mechanism is back: production ends for good and fractions are discarded. A leftover buffer
     * is saved apart from the profile and delivered as space appears in the output.
     */
    public void finish(UUID farmId) {
        var farm = running.remove(farmId);
        if (farm == null) return;
        var buffer = farm.emulation.buffer();
        if (buffer.isEmpty()) {
            farm.chunks.forEach(tickets::release);
            return;
        }
        store.saveDrain(farmId, buffer);
        draining.put(farmId, new Draining(farm.farm, farm.chunks, buffer));
    }

    /** Continues delivering a leftover buffer saved before a restart. */
    public void drain(MarkedFarm farm, Map<Material, Integer> buffer) {
        var chunks = outputChunks(farm);
        tickets.acquireAll(chunks);
        draining.put(farm.id(), new Draining(farm, chunks, buffer));
    }

    /**
     * The farm's registration is removed: delivery of its leftover buffer stops and the output chunk is released.
     * Its saved files are deleted with the registration. A farm in stasis is never removed, so production is not
     * affected.
     */
    public void stopDraining(UUID farmId) {
        var farm = draining.remove(farmId);
        if (farm != null) farm.chunks.forEach(tickets::release);
    }

    /** True while a restored farm still has buffered items waiting for space in its output. */
    public boolean draining(UUID farmId) {
        return draining.containsKey(farmId);
    }

    public void tick() {
        for (var farm : running.values().toArray(Running[]::new)) {
            if (farm.paused || ++farm.elapsed % settings.updateInterval() != 0) continue;
            var before = farm.emulation.state();
            deliver(farm, () -> farm.emulation.update(settings.updateInterval(),
                    farm.output(world)));
            var after = farm.emulation.state();
            // The output's own contents are saved with the world; this keeps both sides of each delivery.
            if (!after.equals(before)) store.saveEmulation(farm.farm.id(), after);
        }
        for (var farm : draining.values().toArray(Draining[]::new)) {
            if (++farm.elapsed % settings.updateInterval() != 0) continue;
            var before = Map.copyOf(farm.buffer);
            if (!deliver(farm, () -> ProfileEmulation.deliver(farm.buffer, farm.output(world)))) continue;
            if (farm.buffer.isEmpty()) {
                store.deleteDrain(farm.farm.id());
                draining.remove(farm.farm.id());
                farm.chunks.forEach(tickets::release);
            } else if (!farm.buffer.equals(before)) {
                store.saveDrain(farm.farm.id(), farm.buffer);
            }
        }
    }

    /**
     * Runs one delivery. A missing or rejecting output keeps every item: the buffer waits and production stays
     * paused until the output accepts items again. The creator is told once per loss.
     *
     * @return whether the output accepted the delivery
     */
    private boolean deliver(Entry farm, Runnable delivery) {
        try {
            delivery.run();
            farm.reportedLoss = false;
            return true;
        } catch (IllegalStateException | IllegalArgumentException error) {
            if (!farm.reportedLoss) {
                farm.reportedLoss = true;
                world.notify(farm.farm.creatorId(), "Выходной контейнер фермы " + farm.farm.id()
                        + " недоступен: " + error.getMessage() + " Буфер выработки сохранён и будет перенесён, когда"
                        + " контейнер снова примет предметы.");
            }
            return false;
        }
    }

    /** Plugin shutdown: saves progress and releases tickets; {@link #start} continues it after the restart. */
    public void stopAll() {
        for (var farm : running.values()) {
            store.saveEmulation(farm.farm.id(), farm.emulation.state());
            farm.chunks.forEach(tickets::release);
        }
        running.clear();
        for (var farm : draining.values()) {
            store.saveDrain(farm.farm.id(), farm.buffer);
            farm.chunks.forEach(tickets::release);
        }
        draining.clear();
    }

    private static Set<ChunkPosition> outputChunks(MarkedFarm farm) {
        var chunks = new LinkedHashSet<ChunkPosition>();
        for (var block : farm.output().blocks()) chunks.add(ChunkPosition.of(block));
        return chunks;
    }
}
