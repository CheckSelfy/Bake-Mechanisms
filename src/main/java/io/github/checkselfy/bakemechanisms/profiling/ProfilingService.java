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
    public interface ProgressListener {
        default void started(MarkedFarm farm) { }
        default void started(MarkedFarm farm, int durationTicks) { started(farm); }

        default void progressed(MarkedFarm farm, long elapsedTicks) { }

        default void ended(UUID farmId) { }
    }

    private final FarmRegistry registry;
    private final ProfilingWorld world;
    private final ProfilingSettings settings;
    private final Map<UUID, Session> sessions = new LinkedHashMap<>();
    private final ChunkTickets tickets;
    private final Predicate<UUID> draining;
    private final ProgressListener progressListener;

    private static final class Session {
        final MarkedFarm farm;
        final Set<ChunkPosition> chunks;
        final ProfileCalculator calculator;
        final ProfilingIsolation isolation;
        final int durationTicks;
        long elapsed;

        Session(MarkedFarm farm, Set<ChunkPosition> chunks, ProfileCalculator calculator, int durationTicks) {
            this.farm = farm;
            this.chunks = chunks;
            this.calculator = calculator;
            this.isolation = new ProfilingIsolation(farm);
            this.durationTicks = durationTicks;
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
        this(registry, world, settings, tickets, draining, new ProgressListener() { });
    }

    public ProfilingService(FarmRegistry registry, ProfilingWorld world, ProfilingSettings settings,
                            ChunkTickets tickets, Predicate<UUID> draining, ProgressListener progressListener) {
        this.draining = Objects.requireNonNull(draining, "draining");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.world = Objects.requireNonNull(world, "world");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.tickets = Objects.requireNonNull(tickets, "tickets");
        this.progressListener = Objects.requireNonNull(progressListener, "progressListener");
    }

    public void start(UUID farmId, UUID actorId) {
        start(farmId, actorId, ProfilingSettings.DURATION_TICKS);
    }

    public void start(UUID farmId, UUID actorId, int durationTicks) {
        if (durationTicks != ProfilingSettings.DURATION_TICKS && durationTicks != ProfilingSettings.LONG_DURATION_TICKS)
            throw new IllegalArgumentException("Длительность замера должна быть 3600 или 36000 тиков.");
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
        ProfilingIsolation.requireAdjacentOutputs(farm.mechanism(), farm.outputs());
        var area = farm.mechanism().expanded(ProfilingIsolation.MARGIN);
        if (sessions.values().stream().anyMatch(session -> session.isolation.area().overlaps(area))) {
            throw new IllegalArgumentException("Защитная область пересекает другую профилируемую ферму.");
        }
        if (registry.all().stream().anyMatch(other -> !other.id().equals(farm.id())
                && (area.overlaps(other.mechanism()) || other.outputBlocks().anyMatch(area::contains)))) {
            throw new IllegalArgumentException("Защитная область захватывает механизм или выход другой фермы. Разнесите фермы.");
        }
        if (world.hasPlayerInside(area)) {
            throw new IllegalArgumentException("Внутри защитной области находится игрок. Отойдите на два блока от механизма и повторите.");
        }
        var chunks = chunksOf(farm);
        tickets.acquireAll(chunks);
        List<OutputSnapshot> baseline;
        ProfilingIsolation.Sources sources;
        try {
            sources = world.validateIsolation(farm);
            baseline = readOutputs(farm);
            farm.outputs().forEach(world::lockOutput);
        } catch (IllegalStateException error) {
            chunks.forEach(tickets::release);
            throw new IllegalArgumentException(error.getMessage(), error);
        } catch (RuntimeException error) {
            chunks.forEach(tickets::release);
            throw error;
        }
        registry.markProfiling(farm.id());
        var session = new Session(farm, chunks, new ProfileCalculator(baseline, settings), durationTicks);
        session.isolation.initialize(sources);
        sessions.put(farm.id(), session);
        progressListener.started(farm, durationTicks);
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
        return session.durationTicks - session.elapsed;
    }

    /** Farms whose mechanism and output are currently protected. */
    public List<MarkedFarm> activeFarms() {
        return sessions.values().stream().map(session -> session.farm).toList();
    }

    public List<ProfilingIsolation> isolations() { return sessions.values().stream().map(session -> session.isolation).toList(); }

    public void reject(UUID farmId, String reason) {
        var session = sessions.get(farmId);
        if (session != null) abort(session, reason);
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
            boolean last = session.elapsed >= session.durationTicks;
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
            else progressListener.progressed(session.farm, session.elapsed);
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
        progressListener.ended(session.farm.id());
    }

    private static Set<ChunkPosition> chunksOf(MarkedFarm farm) {
        var chunks = new LinkedHashSet<ChunkPosition>();
        var area = farm.mechanism().expanded(ProfilingIsolation.MARGIN);
        var min = area.min();
        var max = area.max();
        for (int x = min.x() >> 4; x <= max.x() >> 4; x++) {
            for (int z = min.z() >> 4; z <= max.z() >> 4; z++) {
                chunks.add(new ChunkPosition(min.worldId(), x, z));
            }
        }
        farm.outputBlocks().forEach(block -> chunks.add(ChunkPosition.of(block)));
        return chunks;
    }
}
