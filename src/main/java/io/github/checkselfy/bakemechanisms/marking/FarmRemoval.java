package io.github.checkselfy.bakemechanisms.marking;

import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.MarkedFarm;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Removes a registration in two steps: a request, then a confirmation by the same creator within
 * {@link #CONFIRMATION_MILLIS}. Only a farm whose original mechanism stands in the world can be removed, so a
 * farm in stasis never loses its snapshot. Server thread only.
 */
public final class FarmRemoval {
    public static final long CONFIRMATION_SECONDS = 30;
    public static final long CONFIRMATION_MILLIS = CONFIRMATION_SECONDS * 1000;

    private record Request(UUID farmId, long expiresAt) { }

    private final FarmRegistry registry;
    private final Consumer<UUID> deleteData;
    private final Consumer<UUID> stopDraining;
    private final LongSupplier clockMillis;
    private final Map<UUID, Request> requests = new HashMap<>();

    /**
     * @param deleteData    deletes every saved file of the farm; refuses one with an active snapshot by throwing
     * @param stopDraining stops delivering the farm's leftover buffer and releases its chunk tickets
     */
    public FarmRemoval(FarmRegistry registry, Consumer<UUID> deleteData, Consumer<UUID> stopDraining,
                       LongSupplier clockMillis) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.deleteData = Objects.requireNonNull(deleteData, "deleteData");
        this.stopDraining = Objects.requireNonNull(stopDraining, "stopDraining");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    /** Checks that the farm can be removed and waits for {@link #confirm}; changes nothing yet. */
    public MarkedFarm request(UUID farmId, UUID actorId) {
        var farm = requireRemovable(farmId, actorId);
        requests.put(actorId, new Request(farmId, clockMillis.getAsLong() + CONFIRMATION_MILLIS));
        return farm;
    }

    /** Removes the farm requested by the same player; every check is repeated because the farm may have changed. */
    public void confirm(UUID farmId, UUID actorId) {
        var farm = registry.requireCreator(farmId, actorId);
        var request = requests.get(actorId);
        if (request == null || !request.farmId().equals(farmId) || clockMillis.getAsLong() > request.expiresAt()) {
            throw new IllegalArgumentException("Сначала запросите удаление: /bake delete " + farm.reference()
                    + ". Подтверждение действует " + CONFIRMATION_SECONDS + " секунд.");
        }
        requireRemovable(farmId, actorId);
        requests.remove(actorId);
        try {
            deleteData.accept(farmId);
        } catch (UncheckedIOException | IllegalStateException error) {
            throw new IllegalArgumentException("Данные фермы не удалены, регистрация сохранена: "
                    + error.getMessage(), error);
        }
        stopDraining.accept(farmId);
        registry.remove(farmId);
    }

    private MarkedFarm requireRemovable(UUID farmId, UUID actorId) {
        var farm = registry.requireCreator(farmId, actorId);
        switch (farm.state()) {
            case STASIS -> throw new IllegalArgumentException(
                    "Ферма в стазисе: сначала восстановите механизм (/bake restore " + farm.reference() + ").");
            case PROFILING -> throw new IllegalArgumentException(
                    "Идёт профилирование: сначала отмените его (/bake cancel " + farm.reference() + ").");
            case NEEDS_PROFILING, READY -> { }
        }
        return farm;
    }
}
