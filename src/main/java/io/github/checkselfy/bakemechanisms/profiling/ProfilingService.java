package io.github.checkselfy.bakemechanisms.profiling;

import io.github.checkselfy.bakemechanisms.marking.FarmRegistry;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.MarkedFarm;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.State;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Runs independent profiling sessions. {@link #tick()} must be called once per server tick;
 * all methods run on the server thread.
 */
public final class ProfilingService {
    private final FarmRegistry registry;
    private final ProfilingWorld world;
    private final ProfilingSettings settings;
    private final Map<UUID, Session> sessions = new LinkedHashMap<>();
    private final Map<ChunkPosition, Integer> ticketHolders = new HashMap<>();

    private static final class Session {
        final MarkedFarm farm;
        final Set<ChunkPosition> chunks;
        final ProfileCalculator calculator;
        long elapsed;

        Session(MarkedFarm farm, Set<ChunkPosition> chunks, ProfileCalculator calculator) {
            this.farm = farm;
            this.chunks = chunks;
            this.calculator = calculator;
        }
    }

    public ProfilingService(FarmRegistry registry, ProfilingWorld world, ProfilingSettings settings) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.world = Objects.requireNonNull(world, "world");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public void start(UUID farmId, UUID actorId) {
        var farm = registry.requireCreator(farmId, actorId);
        if (sessions.containsKey(farm.id())) throw new IllegalArgumentException("Ферма уже профилируется.");
        if (farm.state() != State.NEEDS_PROFILING) {
            throw new IllegalArgumentException("Профилирование запускается только из NEEDS_PROFILING, сейчас "
                    + farm.state() + ".");
        }
        if (world.hasPlayerInside(farm.mechanism())) {
            throw new IllegalArgumentException("Внутри механизма находится игрок. Выйдите из кубоида и повторите.");
        }
        var acquired = new ArrayList<ChunkPosition>();
        OutputSnapshot baseline;
        try {
            for (var chunk : chunksOf(farm)) {
                acquire(chunk);
                acquired.add(chunk);
            }
            baseline = world.readOutput(farm.output());
        } catch (IllegalStateException error) {
            acquired.forEach(this::release);
            throw new IllegalArgumentException(error.getMessage(), error);
        } catch (RuntimeException error) {
            acquired.forEach(this::release);
            throw error;
        }
        var chunks = new LinkedHashSet<>(acquired);
        world.lockOutput(farm.output());
        registry.markProfiling(farm.id());
        sessions.put(farm.id(), new Session(farm, chunks, new ProfileCalculator(baseline, settings)));
    }

    /** Farms whose mechanism and output are currently protected. */
    public List<MarkedFarm> activeFarms() {
        return sessions.values().stream().map(session -> session.farm).toList();
    }

    /** Removes protection and tickets; the original mechanism is never touched. */
    public void cancel(UUID farmId, UUID actorId) {
        var farm = registry.requireCreator(farmId, actorId);
        var session = sessions.get(farm.id());
        if (session == null) throw new IllegalArgumentException("Ферма сейчас не профилируется.");
        end(session);
        registry.markNeedsProfiling(farm.id());
    }

    public void tick() {
        for (var session : new ArrayList<>(sessions.values())) {
            session.elapsed++;
            boolean last = session.elapsed >= ProfilingSettings.DURATION_TICKS;
            boolean due = last || session.elapsed % settings.snapshotInterval() == 0;
            if (due && !session.calculator.saturated()) {
                try {
                    session.calculator.record(session.elapsed, world.readOutput(session.farm.output()));
                } catch (IllegalStateException error) {
                    abort(session, error.getMessage());
                    continue;
                }
            }
            if (last) finish(session);
        }
    }

    /** Plugin shutdown: unfinished profiling never survives a restart. */
    public void cancelAll() {
        for (var session : new ArrayList<>(sessions.values())) {
            end(session);
            registry.markNeedsProfiling(session.farm.id());
        }
    }

    private void abort(Session session, String reason) {
        end(session);
        registry.markNeedsProfiling(session.farm.id());
        world.notify(session.farm.creatorId(), "Профилирование фермы " + session.farm.id() + " прервано: " + reason);
    }

    private void finish(Session session) {
        end(session);
        var id = session.farm.id();
        switch (session.calculator.result()) {
            case ProfileCalculator.Result.Accepted accepted -> {
                registry.markReady(id, accepted.profile());
                world.notify(session.farm.creatorId(), "Профилирование фермы " + id + " завершено: READY.");
            }
            case ProfileCalculator.Result.Rejected rejected -> {
                registry.markNeedsProfiling(id);
                world.notify(session.farm.creatorId(), "Профиль фермы " + id + " отклонён: " + rejected.reason()
                        + " Состояние: NEEDS_PROFILING.");
            }
        }
    }

    private void end(Session session) {
        sessions.remove(session.farm.id());
        session.chunks.forEach(this::release);
    }

    private static Set<ChunkPosition> chunksOf(MarkedFarm farm) {
        var chunks = new LinkedHashSet<ChunkPosition>();
        var min = farm.mechanism().min();
        var max = farm.mechanism().max();
        for (int x = min.x() >> 4; x <= max.x() >> 4; x++) {
            for (int z = min.z() >> 4; z <= max.z() >> 4; z++) {
                chunks.add(new ChunkPosition(min.worldId(), x, z));
            }
        }
        for (var block : farm.output().blocks()) {
            chunks.add(new ChunkPosition(block.worldId(), block.x() >> 4, block.z() >> 4));
        }
        return chunks;
    }

    private void acquire(ChunkPosition chunk) {
        if (ticketHolders.merge(chunk, 1, Integer::sum) == 1) world.addChunkTicket(chunk);
    }

    private void release(ChunkPosition chunk) {
        if (ticketHolders.merge(chunk, -1, Integer::sum) == 0) {
            ticketHolders.remove(chunk);
            world.removeChunkTicket(chunk);
        }
    }
}
