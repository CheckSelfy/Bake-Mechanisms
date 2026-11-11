package io.github.checkselfy.bakemechanisms.profiling;

import io.github.checkselfy.bakemechanisms.marking.FarmRegistry;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.MarkedFarm;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.State;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Runs independent profiling sessions. {@link #tick()} must be called once per server tick;
 * all methods run on the server thread.
 */
public final class ProfilingService {
    private final FarmRegistry registry;
    private final ProfilingWorld world;
    private final ProfilingSettings settings;
    private final Map<UUID, Session> sessions = new LinkedHashMap<>();
    private final ChunkTickets tickets;
    private final Predicate<UUID> draining;

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
        this(registry, world, settings, new ChunkTickets(world));
    }

    /** @param tickets shared with other features that force-load chunks of the same plugin */
    public ProfilingService(FarmRegistry registry, ProfilingWorld world, ProfilingSettings settings,
                            ChunkTickets tickets) {
        this(registry, world, settings, tickets, farmId -> false);
    }

    /**
     * @param draining whether a restored farm still delivers its leftover buffer; those items would be counted
     *                 as production, so profiling waits for the delivery to finish
     */
    public ProfilingService(FarmRegistry registry, ProfilingWorld world, ProfilingSettings settings,
                            ChunkTickets tickets, Predicate<UUID> draining) {
        this.draining = Objects.requireNonNull(draining, "draining");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.world = Objects.requireNonNull(world, "world");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.tickets = Objects.requireNonNull(tickets, "tickets");
    }

    public void start(UUID farmId, UUID actorId) {
        var farm = registry.requireCreator(farmId, actorId);
        if (sessions.containsKey(farm.id())) throw new IllegalArgumentException("Ферма уже профилируется.");
        if (farm.state() != State.NEEDS_PROFILING) {
            throw new IllegalArgumentException("Профилирование запускается только из NEEDS_PROFILING, сейчас "
                    + farm.state() + ".");
        }
        if (draining.test(farm.id())) {
            throw new IllegalArgumentException("Остаток буфера выработки ещё переносится в выходной контейнер."
                    + " Освободите в нём место и повторите профилирование.");
        }
        if (world.hasPlayerInside(farm.mechanism())) {
            throw new IllegalArgumentException("Внутри механизма находится игрок. Выйдите из кубоида и повторите.");
        }
        var chunks = chunksOf(farm);
        tickets.acquireAll(chunks);
        List<OutputSnapshot> baseline;
        try {
            baseline = readOutputs(farm);
        } catch (IllegalStateException error) {
            chunks.forEach(tickets::release);
            throw new IllegalArgumentException(error.getMessage(), error);
        } catch (RuntimeException error) {
            chunks.forEach(tickets::release);
            throw error;
        }
        farm.outputs().forEach(world::lockOutput);
        registry.markProfiling(farm.id());
        var session = new Session(farm, chunks, new ProfileCalculator(baseline, settings));
        sessions.put(farm.id(), session);
        if (session.calculator.saturated()) reportFull(session);
    }

    /**
     * Server ticks left until the farm's profiling finishes, for speeding the server up to that point.
     *
     * @throws IllegalArgumentException when the actor is not the creator or the farm is not being profiled
     */
    public long remainingTicks(UUID farmId, UUID actorId) {
        var farm = registry.requireCreator(farmId, actorId);
        var session = sessions.get(farm.id());
        if (session == null) throw new IllegalArgumentException("Ферма сейчас не профилируется.");
        return ProfilingSettings.DURATION_TICKS - session.elapsed;
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
                    session.calculator.record(session.elapsed, readOutputs(session.farm));
                } catch (IllegalStateException error) {
                    abort(session, error.getMessage());
                    continue;
                }
                if (session.calculator.saturated()) reportFull(session);
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

    private List<OutputSnapshot> readOutputs(MarkedFarm farm) {
        return farm.outputs().stream().map(world::readOutput).toList();
    }

    /** Production after the first full output is not measured; the creator can rearrange the output and retry. */
    private void reportFull(Session session) {
        int index = session.calculator.fullOutput();
        var output = session.farm.outputs().get(index);
        world.notify(session.farm.creatorId(), "Ферма " + session.farm.title() + ": выходной контейнер №"
                + (index + 1) + " (" + output.describe() + ") заполнился на тике " + session.elapsed
                + ". Дальнейшая выработка всех выходных контейнеров в профиль не входит. Чтобы замер был длиннее,"
                + " разгружайте выходные контейнеры в промежуточный сундук или бочку.");
    }

    private void abort(Session session, String reason) {
        end(session);
        registry.markNeedsProfiling(session.farm.id());
        world.notify(session.farm.creatorId(), "Профилирование фермы " + session.farm.title() + " прервано: " + reason);
    }

    private void finish(Session session) {
        end(session);
        var id = session.farm.id();
        var label = session.farm.title();
        switch (session.calculator.result()) {
            case ProfileCalculator.Result.Accepted accepted -> {
                registry.markReady(id, accepted.profile());
                world.notify(session.farm.creatorId(), "Профилирование фермы " + label + " завершено: READY.");
            }
            case ProfileCalculator.Result.Rejected rejected -> {
                registry.markNeedsProfiling(id);
                world.notify(session.farm.creatorId(), "Профиль фермы " + label + " отклонён: " + rejected.reason()
                        + " Состояние: NEEDS_PROFILING.");
            }
        }
    }

    private void end(Session session) {
        sessions.remove(session.farm.id());
        session.chunks.forEach(tickets::release);
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
        farm.outputBlocks().forEach(block -> chunks.add(ChunkPosition.of(block)));
        return chunks;
    }
}
