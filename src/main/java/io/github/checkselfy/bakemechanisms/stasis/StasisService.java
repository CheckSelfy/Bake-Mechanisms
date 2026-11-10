package io.github.checkselfy.bakemechanisms.stasis;

import io.github.checkselfy.bakemechanisms.marking.FarmRegistry;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.MarkedFarm;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.State;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owns snapshot lifecycle; does not produce items or modify the emulation buffer. Server thread only. */
public final class StasisService {
    private final FarmRegistry registry;
    private final StasisWorld world;
    private final SnapshotStore store;
    private final Map<UUID, SavedSnapshot> active = new LinkedHashMap<>();

    public StasisService(FarmRegistry registry, StasisWorld world, SnapshotStore store) {
        this.registry = registry;
        this.world = world;
        this.store = store;
    }

    /** Re-register protected snapshots without restoring the original mechanism. */
    public void recover() throws IOException {
        for (var snapshot : store.loadActive()) {
            registry.recoverStasis(snapshot.farm());
            active.put(snapshot.farm().id(), snapshot);
        }
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
        registry.markStasis(farmId);
        try {
            world.removeEntities(farm, snapshot.data());
            world.fill(farm.mechanism());
        } catch (RuntimeException error) {
            refill(farm, error);
            throw new IllegalArgumentException("Ошибка замены механизма. Снимок сохранён; используйте /bake restore "
                    + farmId + ".", error);
        }
    }

    public void restore(UUID farmId, UUID actorId) {
        var farm = registry.requireCreator(farmId, actorId);
        var snapshot = active.get(farmId);
        if (farm.state() != State.STASIS || snapshot == null) {
            throw new IllegalArgumentException("Ферма не находится в стазисе.");
        }
        requireNoPlayers(farm);
        try {
            world.restore(farm, snapshot.data());
            store.deactivate(farmId);
        } catch (IOException | RuntimeException error) {
            refill(farm, error);
            throw new IllegalArgumentException("Восстановление не завершено. Снимок и защита сохранены; повторите команду.", error);
        }
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
