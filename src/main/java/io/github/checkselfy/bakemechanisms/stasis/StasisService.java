package io.github.checkselfy.bakemechanisms.stasis;

import io.github.checkselfy.bakemechanisms.marking.FarmRegistry;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.MarkedFarm;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.State;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Owns snapshot lifecycle and tells {@link StasisProduction} when the original mechanism leaves or returns.
 * Server thread only.
 *
 * <p>Exactly one production stream exists per farm while its complete filler stands in for the mechanism: it is
 * started once (on entry, on recovery, or once a failed restore puts the filler back after a failed entry never
 * started it), paused while the original is being placed and finished once the original is back. It is never
 * started twice, and never while any part of the original may still be in place.
 */
public final class StasisService {
    private final FarmRegistry registry;
    private final StasisWorld world;
    private final SnapshotStore store;
    private final Map<UUID, SavedSnapshot> active = new LinkedHashMap<>();
    /** Farms whose production stream was started and not yet finished. */
    private final Set<UUID> producing = new HashSet<>();
    private final StasisProduction production;
    private final FillerStyle filler;

    public StasisService(FarmRegistry registry, StasisWorld world, SnapshotStore store) {
        this(registry, world, store, StasisProduction.NONE);
    }

    public StasisService(FarmRegistry registry, StasisWorld world, SnapshotStore store,
                         StasisProduction production) {
        this(registry,world,store,production,FillerStyle.DEFAULT);
    }

    public StasisService(FarmRegistry registry, StasisWorld world, SnapshotStore store,
                         StasisProduction production,FillerStyle filler) {
        this.registry = registry;
        this.world = world;
        this.store = store;
        this.production = production;
        this.filler = java.util.Objects.requireNonNull(filler,"filler");
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
        for (var snapshot : active.values()) startProduction(snapshot.farm());
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
            snapshot = new SavedSnapshot(farm.with(State.STASIS, farm.profile()), data,filler);
            store.save(snapshot);
        } catch (IOException | IllegalStateException error) {
            throw new IllegalArgumentException("Снимок не сохранён, мир не изменён: " + error.getMessage(), error);
        }
        // Protect the entire region before starting a possibly partial replacement.
        active.put(farmId, snapshot);
        var stasisFarm = registry.markStasis(farmId);
        // Capture, save, removal and filler placement run in this one server-thread call: no tick happens in between,
        // so no entity can spawn, die, mount or move between the snapshot and the removal of its originals.
        try {
            world.removeEntities(farm, snapshot.data());
            world.fill(farm.mechanism(),snapshot.filler());
            world.verifyHandoff(farm, snapshot.data());
        } catch (RuntimeException error) {
            // Only a complete filler stands in for the mechanism; a remaining original must not run alongside.
            if (refill(farm, error)) startProduction(stasisFarm);
            throw new IllegalArgumentException("Ошибка замены механизма. Снимок сохранён; используйте /bake unstasis "
                    + farmId + ".", error);
        }
        startProduction(stasisFarm);
    }

    public void restore(UUID farmId, UUID actorId) {
        var farm = registry.requireCreator(farmId, actorId);
        var snapshot = active.get(farmId);
        if (farm.state() != State.STASIS || snapshot == null) {
            throw new IllegalArgumentException("Ферма не находится в стазисе.");
        }
        requireNoPlayers(farm);
        world.validateRestore(farm, snapshot.data());
        boolean started = producing.contains(farmId);
        if (started) production.pause(farmId);
        try {
            world.restore(farm, snapshot.data());
            store.deactivate(farmId);
        } catch (IOException | RuntimeException error) {
            // Production stays paused while any part of the original remains in place.
            if (refill(farm, error)) {
                // A failed entry whose rollback also failed never started production; the filler is complete now.
                if (started) production.resume(farmId);
                else startProduction(farm);
            }
            throw new IllegalArgumentException("Восстановление не завершено. Снимок и защита сохранены; повторите команду.", error);
        }
        // Normal operation: the leftover buffer is saved before the profile, and emulation.dat with it, is
        // dropped. A process crash between deactivate and this point is outside the MVP guarantees.
        if (producing.remove(farmId)) production.finish(farmId);
        registry.markNeedsProfiling(farmId);
        active.remove(farmId);
    }

    /**
     * Starts the farm's only production stream. A farm leaves READY only through this service, so a stale entry can
     * remain only when another instance restored it (a restart); the emulation itself rejects a second running stream.
     */
    private void startProduction(MarkedFarm farm) {
        producing.add(farm.id());
        production.start(farm);
    }

    /** Whether the farm's production stream runs or is paused for a restore. */
    public boolean producing(UUID farmId) {
        return producing.contains(farmId);
    }

    private void requireNoPlayers(MarkedFarm farm) {
        boolean occupied;
        try {
            occupied = world.hasPlayerInside(farm.mechanism());
        } catch (IllegalStateException error) {
            throw new IllegalArgumentException("Мир фермы недоступен: " + error.getMessage(), error);
        }
        if (occupied) {
            throw new IllegalArgumentException("Кубоид занят игроком онлайн или сохранённой позицией отключившегося игрока. "
                    + "Игрок должен войти на сервер и выйти из кубоида; затем повторите команду.");
        }
    }

    /** @return whether the whole cuboid is filler again */
    private boolean refill(MarkedFarm farm, Throwable failure) {
        try {
            var snapshot = active.get(farm.id());
            if (snapshot != null) world.removeEntities(farm, snapshot.data());
            world.fill(farm.mechanism(),snapshot.filler());
            return true;
        } catch (RuntimeException rollbackError) {
            if (failure != rollbackError) failure.addSuppressed(rollbackError);
            return false;
        }
    }
}
