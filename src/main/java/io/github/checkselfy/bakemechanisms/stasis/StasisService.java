package io.github.checkselfy.bakemechanisms.stasis;

import io.github.checkselfy.bakemechanisms.marking.FarmRegistry;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.MarkedFarm;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.State;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns snapshot lifecycle and tells {@link StasisProduction} when the original mechanism leaves or returns.
 * Server thread only.
 */
public final class StasisService {
    private final FarmRegistry registry;
    private final StasisWorld world;
    private final SnapshotStore store;
    private final Map<UUID, SavedSnapshot> active = new LinkedHashMap<>();
    private final StasisProduction production;

    public StasisService(FarmRegistry registry, StasisWorld world, SnapshotStore store) {
        this(registry, world, store, StasisProduction.NONE);
    }

    public StasisService(FarmRegistry registry, StasisWorld world, SnapshotStore store,
                         StasisProduction production) {
        this.registry = registry;
        this.world = world;
        this.store = store;
        this.production = production;
    }

    /**
     * Re-register protected snapshots and continue their production without restoring the original mechanism. A farm stored as STASIS without
     * an active snapshot was restored right before a crash (the manifest is removed only after a successful
     * restore), so it returns to NEEDS_PROFILING.
     *
     * @return farms moved to NEEDS_PROFILING for lack of a snapshot
     */
    public List<UUID> recover() throws IOException {
        for (var snapshot : store.loadActive()) {
            registry.recoverStasis(snapshot.farm());
            active.put(snapshot.farm().id(), snapshot);
        }
        for (var snapshot : active.values()) production.start(snapshot.farm());
        var orphaned = registry.all().stream()
                .filter(farm -> farm.state() == State.STASIS && !active.containsKey(farm.id()))
                .map(MarkedFarm::id).toList();
        orphaned.forEach(registry::markNeedsProfiling);
        return orphaned;
    }

    public List<MarkedFarm> activeFarms() {
        return active.values().stream().map(SavedSnapshot::farm).toList();
    }

    public void enter(UUID farmId, UUID actorId) {
        var farm = registry.requireCreator(farmId, actorId);
        if (farm.state() != State.READY) throw new IllegalArgumentException("Стазис доступен только из READY.");
        requireNoPlayers(farm);
        SavedSnapshot snapshot;
        try {
            var data = world.capture(farm);
            snapshot = new SavedSnapshot(new MarkedFarm(farm.id(), farm.creatorId(), farm.mechanism(),
                    farm.output(), State.STASIS, farm.profile()), data);
            store.save(snapshot);
        } catch (IOException | IllegalStateException error) {
            throw new IllegalArgumentException("Снимок не сохранён, мир не изменён: " + error.getMessage(), error);
        }
        // Protect the entire region before starting a possibly partial replacement.
        active.put(farmId, snapshot);
        var stasisFarm = registry.markStasis(farmId);
        try {
            world.removeEntities(farm, snapshot.data());
            world.fill(farm.mechanism());
        } catch (RuntimeException error) {
            refill(farm, error);
            // The filler stands in for the mechanism either way, so the farm keeps producing.
            production.start(stasisFarm);
            throw new IllegalArgumentException("Ошибка замены механизма. Снимок сохранён; используйте /bake restore "
                    + farmId + ".", error);
        }
        production.start(stasisFarm);
    }

    public void restore(UUID farmId, UUID actorId) {
        var farm = registry.requireCreator(farmId, actorId);
        var snapshot = active.get(farmId);
        if (farm.state() != State.STASIS || snapshot == null) {
            throw new IllegalArgumentException("Ферма не находится в стазисе.");
        }
        requireNoPlayers(farm);
        production.pause(farmId);
        try {
            world.restore(farm, snapshot.data());
            store.deactivate(farmId);
        } catch (IOException | RuntimeException error) {
            refill(farm, error);
            production.resume(farmId);
            throw new IllegalArgumentException("Восстановление не завершено. Снимок и защита сохранены; повторите команду.", error);
        }
        // The leftover buffer is set aside before the profile, and the emulation state with it, is dropped.
        production.finish(farmId);
        registry.markNeedsProfiling(farmId);
        active.remove(farmId);
    }

    private void requireNoPlayers(MarkedFarm farm) {
        boolean occupied;
        try {
            occupied = world.hasPlayerInside(farm.mechanism());
        } catch (IllegalStateException error) {
            throw new IllegalArgumentException("Мир фермы недоступен: " + error.getMessage(), error);
        }
        if (occupied) {
            throw new IllegalArgumentException("Внутри механизма находится игрок. Выйдите из кубоида и повторите.");
        }
    }

    private void refill(MarkedFarm farm, Throwable failure) {
        try {
            var snapshot = active.get(farm.id());
            if (snapshot != null) world.removeEntities(farm, snapshot.data());
            world.fill(farm.mechanism());
        } catch (RuntimeException rollbackError) {
            if (failure != rollbackError) failure.addSuppressed(rollbackError);
        }
    }
}
