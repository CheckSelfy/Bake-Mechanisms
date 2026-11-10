package io.github.checkselfy.bakemechanisms.marking;

import io.github.checkselfy.bakemechanisms.profiling.ProductionProfile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** In-memory registration; call on the server thread. Persistence belongs to issue #8. */
public final class FarmRegistry {
    public static final long DEFAULT_MAX_VOLUME = 262_144;

    public enum State { NEEDS_PROFILING, PROFILING, READY, STASIS }

    /** @param profile valid production profile; present in READY and STASIS */
    public record MarkedFarm(UUID id, UUID creatorId, Cuboid mechanism,
                             OutputContainer output, State state, ProductionProfile profile) {
        public MarkedFarm {
            if ((state == State.READY || state == State.STASIS) != (profile != null)) {
                throw new IllegalArgumentException("Профиль хранится только в READY и STASIS.");
            }
        }
    }

    private final long maxVolume;
    private final Map<UUID, MarkedFarm> farms = new LinkedHashMap<>();

    public FarmRegistry(long maxVolume) {
        if (maxVolume < 1) throw new IllegalArgumentException("Предел объёма должен быть положительным.");
        this.maxVolume = maxVolume;
    }

    public MarkedFarm register(UUID creatorId, Cuboid mechanism, OutputContainer output) {
        Objects.requireNonNull(creatorId, "creatorId");
        Objects.requireNonNull(mechanism, "mechanism");
        Objects.requireNonNull(output, "output");
        if (mechanism.exceedsVolume(maxVolume)) {
            throw new IllegalArgumentException("Превышен предел объёма механизма: " + maxVolume);
        }
        for (var block : output.blocks()) {
            if (!mechanism.min().worldId().equals(block.worldId())) {
                throw new IllegalArgumentException("Механизм и выходной контейнер должны быть в одном мире.");
            }
            if (mechanism.contains(block)) {
                throw new IllegalArgumentException("Выходной контейнер должен находиться вне механизма.");
            }
        }
        for (var farm : farms.values()) {
            if (mechanism.sharesMechanismChunks(farm.mechanism())) {
                throw new IllegalArgumentException("Чанки механизма уже заняты другой фермой.");
            }
            for (var block : output.blocks()) {
                if (farm.output().blocks().contains(block)) {
                    throw new IllegalArgumentException("Выходной контейнер уже назначен другой ферме.");
                }
                if (farm.mechanism().contains(block)) {
                    throw new IllegalArgumentException("Выходной контейнер находится внутри другой фермы.");
                }
            }
            if (farm.output().blocks().stream().anyMatch(mechanism::contains)) {
                throw new IllegalArgumentException("Механизм включает выходной контейнер другой фермы.");
            }
        }
        var farm = new MarkedFarm(UUID.randomUUID(), creatorId, mechanism, output, State.NEEDS_PROFILING, null);
        farms.put(farm.id(), farm);
        return farm;
    }

    /** All access to an existing registration must pass this creator check. */
    public MarkedFarm requireCreator(UUID farmId, UUID actorId) {
        var farm = farms.get(Objects.requireNonNull(farmId, "farmId"));
        if (farm == null) throw new IllegalArgumentException("Ферма не найдена.");
        if (!farm.creatorId().equals(Objects.requireNonNull(actorId, "actorId"))) {
            throw new IllegalArgumentException("Управлять фермой может только её создатель.");
        }
        return farm;
    }

    public MarkedFarm markProfiling(UUID farmId) {
        return replace(farmId, State.PROFILING, null);
    }

    public MarkedFarm markReady(UUID farmId, ProductionProfile profile) {
        return replace(farmId, State.READY, Objects.requireNonNull(profile, "profile"));
    }

    public MarkedFarm markStasis(UUID farmId) {
        var farm = farms.get(Objects.requireNonNull(farmId, "farmId"));
        if (farm == null || farm.state() != State.READY) {
            throw new IllegalArgumentException("Стазис доступен только из READY.");
        }
        return replace(farmId, State.STASIS, farm.profile());
    }

    /** Snapshot recovery only; generic profile/buffer persistence belongs to #8. */
    public void recoverStasis(MarkedFarm farm) {
        if (farm.state() != State.STASIS) throw new IllegalArgumentException("Ожидается STASIS.");
        var existing = farms.get(farm.id());
        if (existing != null && (!existing.creatorId().equals(farm.creatorId())
                || !existing.mechanism().equals(farm.mechanism()) || !existing.output().equals(farm.output()))) {
            throw new IllegalArgumentException("Снимок конфликтует с регистрацией фермы " + farm.id());
        }
        farms.put(farm.id(), farm);
    }

    /** Drops any previous profile: it must never be reused. */
    public MarkedFarm markNeedsProfiling(UUID farmId) {
        return replace(farmId, State.NEEDS_PROFILING, null);
    }

    private MarkedFarm replace(UUID farmId, State state, ProductionProfile profile) {
        var farm = farms.get(Objects.requireNonNull(farmId, "farmId"));
        if (farm == null) throw new IllegalArgumentException("Ферма не найдена.");
        var updated = new MarkedFarm(farm.id(), farm.creatorId(), farm.mechanism(), farm.output(), state, profile);
        farms.put(farmId, updated);
        return updated;
    }

    public List<MarkedFarm> findByCreator(UUID creatorId) {
        Objects.requireNonNull(creatorId, "creatorId");
        return farms.values().stream().filter(farm -> farm.creatorId().equals(creatorId)).toList();
    }
}
