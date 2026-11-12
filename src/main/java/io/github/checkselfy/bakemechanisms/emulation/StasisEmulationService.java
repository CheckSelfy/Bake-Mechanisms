package io.github.checkselfy.bakemechanisms.emulation;

import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.MarkedFarm;
import io.github.checkselfy.bakemechanisms.marking.OutputContainer;
import io.github.checkselfy.bakemechanisms.profiling.ChunkPosition;
import io.github.checkselfy.bakemechanisms.profiling.ChunkTickets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bukkit.Material;

/**
 * Produces items for farms in stasis and, after a restore, delivers their leftover buffer. The output chunks
 * stay loaded for both. {@link #tick()} must be called once per server tick; all methods run on the server
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
        /** The creator was told an output is unavailable; reset by the next successful delivery. */
        boolean reportedLoss;

        Entry(MarkedFarm farm, Set<ChunkPosition> chunks) {
            this.farm = farm;
            this.chunks = chunks;
        }

        OutputContainer output(int index) {
            return farm.outputs().get(index);
        }

        ProfileEmulation.Output output(EmulationWorld world) {
            return (index, type, amount) -> world.insert(output(index), type, amount);
        }
    }

    private static final class Running extends Entry {
        final ProfileEmulation emulation;
        boolean paused;
        /** The creator was told production stopped because an output is full; reset when it resumes. */
        boolean reportedFull;

        Running(MarkedFarm farm, Set<ChunkPosition> chunks, ProfileEmulation emulation) {
            super(farm, chunks);
            this.emulation = emulation;
        }
    }

    private static final class Draining extends Entry {
        final List<Map<Material, Integer>> buffers = new ArrayList<>();

        Draining(MarkedFarm farm, Set<ChunkPosition> chunks, List<Map<Material, Integer>> buffers) {
            super(farm, chunks);
            if (buffers.size() != farm.outputs().size()) {
                throw new IllegalArgumentException("Остаток буфера не соответствует выходным контейнерам фермы.");
            }
            for (var buffer : buffers) {
                var copy = new EnumMap<Material, Integer>(Material.class);
                copy.putAll(buffer);
                this.buffers.add(copy);
            }
        }

        boolean empty() {
            return buffers.stream().allMatch(Map::isEmpty);
        }

        List<Map<Material, Integer>> snapshot() {
            return buffers.stream().map(Map::copyOf).toList();
        }
    }

    public StasisEmulationService(EmulationWorld world, ChunkTickets tickets, EmulationSettings settings,
                                  EmulationStore store) {
        this.world = Objects.requireNonNull(world, "world");
        this.tickets = Objects.requireNonNull(tickets, "tickets");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Starts or continues production of a farm in stasis from {@code state}, keeping its outputs loaded.
     *
     * @throws IllegalStateException when the farm already produces: replacing its stream would drop the buffered
     *                               items and fractions and leak its chunk tickets, and running two would multiply
     *                               its output
     */
    public void start(MarkedFarm farm, EmulationState state) {
        if (running.containsKey(farm.id())) {
            throw new IllegalStateException("Эмуляция фермы " + farm.id() + " уже запущена.");
        }
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
     * is saved apart from the profile and delivered as space appears in the outputs.
     */
    public void finish(UUID farmId) {
        var farm = running.remove(farmId);
        if (farm == null) return;
        if (!farm.emulation.paused()) {
            farm.chunks.forEach(tickets::release);
            return;
        }
        var buffers = farm.emulation.buffers();
        store.saveDrain(farmId, buffers);
        draining.put(farmId, new Draining(farm.farm, farm.chunks, buffers));
    }

    /** Continues delivering a leftover buffer saved before a restart, one buffer per output in order. */
    public void drain(MarkedFarm farm, List<Map<Material, Integer>> buffers) {
        var entry = new Draining(farm, outputChunks(farm), buffers);
        tickets.acquireAll(entry.chunks);
        draining.put(farm.id(), entry);
    }

    /**
     * The farm's registration is removed: delivery of its leftover buffer stops and the output chunks are
     * released. Its saved files are deleted with the registration. A farm in stasis is never removed, so
     * production is not affected.
     */
    public void stopDraining(UUID farmId) {
        var farm = draining.remove(farmId);
        if (farm != null) farm.chunks.forEach(tickets::release);
    }

    /** True while the farm in stasis has a production stream, running or paused. */
    public boolean producing(UUID farmId) {
        return running.containsKey(farmId);
    }

    /** True while a restored farm still has buffered items waiting for space in its outputs. */
    public boolean draining(UUID farmId) {
        return draining.containsKey(farmId);
    }

    public void tick() {
        for (var farm : running.values().toArray(Running[]::new)) {
            if (farm.paused || ++farm.elapsed % settings.updateInterval() != 0) continue;
            var before = farm.emulation.state();
            if (deliver(farm, () -> farm.emulation.update(settings.updateInterval(), farm.output(world)))) {
                reportFull(farm);
            }
            var after = farm.emulation.state();
            // The outputs' own contents are saved with the world; this keeps both sides of each delivery.
            if (!after.equals(before)) store.saveEmulation(farm.farm.id(), after);
        }
        for (var farm : draining.values().toArray(Draining[]::new)) {
            if (++farm.elapsed % settings.updateInterval() != 0) continue;
            var before = farm.snapshot();
            // Even a failed delivery may have served the other outputs.
            deliver(farm, () -> ProfileEmulation.deliver(farm.buffers, farm.output(world)));
            if (farm.empty()) {
                store.deleteDrain(farm.farm.id());
                draining.remove(farm.farm.id());
                farm.chunks.forEach(tickets::release);
            } else if (!farm.snapshot().equals(before)) {
                store.saveDrain(farm.farm.id(), farm.snapshot());
            }
        }
    }

    /** Tells the creator once when a full output stops production of all outputs, and once when it resumes. */
    private void reportFull(Running farm) {
        var blocked = farm.emulation.blockedOutputs();
        if (!blocked.isEmpty() && !farm.reportedFull) {
            farm.reportedFull = true;
            var outputs = blocked.stream()
                    .map(index -> "№" + (index + 1) + " (" + farm.output(index).describe() + ")")
                    .collect(Collectors.joining(", "));
            world.notify(farm.farm.creatorId(), "Ферма " + farm.farm.title() + ": нет места в выходном контейнере "
                    + outputs + ". Производство во все выходные контейнеры приостановлено; оно продолжится, когда во"
                    + " всех выходных контейнерах найдётся место для уже произведённых предметов.");
        } else if (blocked.isEmpty() && farm.reportedFull) {
            farm.reportedFull = false;
            world.notify(farm.farm.creatorId(), "Ферма " + farm.farm.title()
                    + ": место в выходных контейнерах появилось, производство возобновлено.");
        }
    }

    /**
     * Runs one delivery. A missing or rejecting output keeps its items: they wait and production stays paused
     * until the output accepts items again. The creator is told once per loss.
     *
     * @return whether every output accepted the delivery
     */
    private boolean deliver(Entry farm, Runnable delivery) {
        try {
            delivery.run();
            farm.reportedLoss = false;
            return true;
        } catch (IllegalStateException | IllegalArgumentException error) {
            if (!farm.reportedLoss) {
                farm.reportedLoss = true;
                world.notify(farm.farm.creatorId(), "Выходной контейнер фермы " + farm.farm.title()
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
            store.saveDrain(farm.farm.id(), farm.snapshot());
            farm.chunks.forEach(tickets::release);
        }
        draining.clear();
    }

    private static Set<ChunkPosition> outputChunks(MarkedFarm farm) {
        var chunks = new LinkedHashSet<ChunkPosition>();
        farm.outputBlocks().forEach(block -> chunks.add(ChunkPosition.of(block)));
        return chunks;
    }
}
