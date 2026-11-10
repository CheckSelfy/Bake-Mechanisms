package io.github.checkselfy.bakemechanisms.emulation;

import io.github.checkselfy.bakemechanisms.marking.FarmRegistry;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.MarkedFarm;
import io.github.checkselfy.bakemechanisms.profiling.ChunkPosition;
import io.github.checkselfy.bakemechanisms.profiling.ChunkTickets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bukkit.Material;

/**
 * Verification-only path: runs {@link ProfileEmulation} for a READY farm without stasis, while the original
 * mechanism stays in place. The final user cycle (issue #9) drives {@link ProfileEmulation} from stasis
 * instead; this class and its commands are then deleted without changing emulation behavior.
 * {@link #tick()} must be called once per server tick; all methods run on the server thread.
 */
public final class EmulationTrialService {
    private final FarmRegistry registry;
    private final EmulationWorld world;
    private final ChunkTickets tickets;
    private final EmulationSettings settings;
    private final Map<UUID, Trial> trials = new LinkedHashMap<>();

    private static final class Trial {
        final MarkedFarm farm;
        final Set<ChunkPosition> chunks;
        final ProfileEmulation emulation;
        long elapsed;

        Trial(MarkedFarm farm, Set<ChunkPosition> chunks) {
            this.farm = farm;
            this.chunks = chunks;
            this.emulation = new ProfileEmulation(farm.profile());
        }
    }

    public EmulationTrialService(FarmRegistry registry, EmulationWorld world, ChunkTickets tickets,
                                 EmulationSettings settings) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.world = Objects.requireNonNull(world, "world");
        this.tickets = Objects.requireNonNull(tickets, "tickets");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public void start(UUID farmId, UUID actorId) {
        var farm = registry.requireCreator(farmId, actorId);
        if (trials.containsKey(farm.id())) throw new IllegalArgumentException("Эмуляция фермы уже запущена.");
        if (farm.state() != FarmRegistry.State.READY) {
            throw new IllegalArgumentException("Проверочная эмуляция запускается только из READY, сейчас "
                    + farm.state() + ".");
        }
        var chunks = new LinkedHashSet<ChunkPosition>();
        for (var block : farm.output().blocks()) chunks.add(ChunkPosition.of(block));
        tickets.acquireAll(chunks);
        trials.put(farm.id(), new Trial(farm, chunks));
    }

    /**
     * Ends the trial. Fractions are discarded with it.
     *
     * @return buffered whole items that never fit into the output and are dropped with the trial
     */
    public Map<Material, Integer> stop(UUID farmId, UUID actorId) {
        var farm = registry.requireCreator(farmId, actorId);
        var trial = trials.get(farm.id());
        if (trial == null) throw new IllegalArgumentException("Эмуляция фермы не запущена.");
        end(trial);
        return trial.emulation.buffer();
    }

    public void tick() {
        for (var trial : trials.values().toArray(Trial[]::new)) {
            trial.elapsed++;
            if (trial.elapsed % settings.updateInterval() != 0) continue;
            try {
                trial.emulation.update(settings.updateInterval(),
                        (type, amount) -> world.insert(trial.farm.output(), type, amount));
            } catch (IllegalStateException | IllegalArgumentException error) {
                end(trial);
                var dropped = trial.emulation.buffer();
                world.notify(trial.farm.creatorId(), "Проверочная эмуляция фермы " + trial.farm.id()
                        + " остановлена: " + error.getMessage()
                        + (dropped.isEmpty() ? "" : " Буфер выработки отброшен: " + describe(dropped)));
            }
        }
    }

    /** Plugin shutdown: trials never survive a restart. */
    public void stopAll() {
        for (var trial : trials.values().toArray(Trial[]::new)) end(trial);
    }

    /** Player-facing form of a buffer, e.g. {@code cobblestone ×3, kelp ×1}. */
    public static String describe(Map<Material, Integer> items) {
        return items.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(item -> item.getKey().getKey().getKey() + " ×" + item.getValue())
                .collect(Collectors.joining(", "));
    }

    private void end(Trial trial) {
        trials.remove(trial.farm.id());
        trial.chunks.forEach(tickets::release);
    }
}
