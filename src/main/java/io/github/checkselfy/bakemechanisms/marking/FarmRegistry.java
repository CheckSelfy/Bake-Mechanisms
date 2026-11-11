package io.github.checkselfy.bakemechanisms.marking;

import io.github.checkselfy.bakemechanisms.profiling.ProductionProfile;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Registered farms; call on the server thread. Every registration and state change is reported to the change
 * listener, which persists it. {@link #remove} is not reported: the caller deletes the saved data first, so a
 * failed deletion keeps the registration.
 */
public final class FarmRegistry {
    public static final long DEFAULT_MAX_VOLUME = 262_144;
    public static final int DEFAULT_MAX_OUTPUTS = 8;

    public enum State { NEEDS_PROFILING, PROFILING, READY, STASIS }

    /**
     * @param name    optional name chosen by the creator, unique among the creator's farms; null when unnamed
     * @param outputs output containers in a fixed order, which the profile's per-output rates follow
     * @param profile valid production profile; present in READY and STASIS
     */
    public record MarkedFarm(UUID id, UUID creatorId, String name, Cuboid mechanism,
                             List<OutputContainer> outputs, State state, ProductionProfile profile) {
        public MarkedFarm {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(creatorId, "creatorId");
            Objects.requireNonNull(mechanism, "mechanism");
            outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
            if (outputs.isEmpty()) throw new IllegalArgumentException("Нужен хотя бы один выходной контейнер.");
            if (name != null) FarmNames.requireValid(name);
            if ((state == State.READY || state == State.STASIS) != (profile != null)) {
                throw new IllegalArgumentException("Профиль хранится только в READY и STASIS.");
            }
            if (profile != null && profile.outputs().size() != outputs.size()) {
                throw new IllegalArgumentException("Профиль не соответствует числу выходных контейнеров.");
            }
        }

        /** An unnamed farm with a single output container. */
        public MarkedFarm(UUID id, UUID creatorId, Cuboid mechanism, OutputContainer output, State state,
                          ProductionProfile profile) {
            this(id, creatorId, null, mechanism, List.of(output), state, profile);
        }

        public MarkedFarm with(State state, ProductionProfile profile) {
            return new MarkedFarm(id, creatorId, name, mechanism, outputs, state, profile);
        }

        public MarkedFarm withName(String name) {
            return new MarkedFarm(id, creatorId, name, mechanism, outputs, state, profile);
        }

        /** How commands refer to this farm: its name, or the {@code --id} flag with its UUID when unnamed. */
        public String reference() {
            return name != null ? name : FarmArguments.ID_FLAG + " " + id;
        }

        /** Every physical block of every output container. */
        public Stream<BlockPosition> outputBlocks() {
            return outputs.stream().flatMap(output -> output.blocks().stream());
        }

        /** Title for messages: the name with the UUID, or only the UUID when unnamed. */
        public String title() {
            return name != null ? name + " (" + id + ")" : id.toString();
        }
    }

    private final long maxVolume;
    private final int maxOutputs;
    private final Consumer<MarkedFarm> onChange;
    private final Map<UUID, MarkedFarm> farms = new LinkedHashMap<>();

    public FarmRegistry(long maxVolume) {
        this(maxVolume, farm -> { });
    }

    /** @param onChange receives the new state of a farm after each registration or state change */
    public FarmRegistry(long maxVolume, Consumer<MarkedFarm> onChange) {
        this(maxVolume, DEFAULT_MAX_OUTPUTS, onChange);
    }

    /**
     * @param maxOutputs most output containers a new registration may have
     * @param onChange   receives the new state of a farm after each registration or state change
     */
    public FarmRegistry(long maxVolume, int maxOutputs, Consumer<MarkedFarm> onChange) {
        if (maxVolume < 1) throw new IllegalArgumentException("Предел объёма должен быть положительным.");
        if (maxOutputs < 1) {
            throw new IllegalArgumentException("Предел выходных контейнеров должен быть положительным.");
        }
        this.maxVolume = maxVolume;
        this.maxOutputs = maxOutputs;
        this.onChange = Objects.requireNonNull(onChange, "onChange");
    }

    /** Registers an unnamed farm with a single output container. */
    public MarkedFarm register(UUID creatorId, Cuboid mechanism, OutputContainer output) {
        return register(creatorId, null, mechanism, List.of(Objects.requireNonNull(output, "output")));
    }

    /** @param name optional, see {@link FarmNames}; null registers an unnamed farm */
    public MarkedFarm register(UUID creatorId, String name, Cuboid mechanism, List<OutputContainer> outputs) {
        Objects.requireNonNull(creatorId, "creatorId");
        Objects.requireNonNull(mechanism, "mechanism");
        Objects.requireNonNull(outputs, "outputs");
        if (mechanism.exceedsVolume(maxVolume)) {
            throw new IllegalArgumentException("Превышен предел объёма механизма: " + maxVolume);
        }
        if (outputs.isEmpty()) throw new IllegalArgumentException("Выберите хотя бы один выходной контейнер.");
        if (outputs.size() > maxOutputs) {
            throw new IllegalArgumentException("Слишком много выходных контейнеров: " + outputs.size()
                    + ", допустимо не больше " + maxOutputs + ".");
        }
        if (name != null) requireFreeName(creatorId, FarmNames.requireValid(name), null);
        validate(mechanism, outputs);
        io.github.checkselfy.bakemechanisms.profiling.ProfilingIsolation.requireAdjacentOutputs(mechanism, outputs);
        var farm = new MarkedFarm(UUID.randomUUID(), creatorId, name, mechanism, outputs, State.NEEDS_PROFILING,
                null);
        farms.put(farm.id(), farm);
        onChange.accept(farm);
        return farm;
    }

    /**
     * Loads a saved farm under the legacy placement rules so distant outputs do not prevent snapshot recovery.
     * Profiling rechecks output proximity before a new run. The volume and output limits are not
     * rechecked: they govern new registrations, and lowering them must not drop existing farms. Unfinished
     * profiling is never stitched across a restart: a farm saved in {@link State#PROFILING} returns as
     * {@link State#NEEDS_PROFILING}, and only that change is reported to the listener.
     */
    public MarkedFarm restore(MarkedFarm saved) {
        Objects.requireNonNull(saved, "saved");
        if (farms.containsKey(saved.id())) throw new IllegalArgumentException("Ферма уже загружена: " + saved.id());
        validate(saved.mechanism(), saved.outputs());
        if (saved.name() != null) requireFreeName(saved.creatorId(), saved.name(), null);
        var farm = saved.state() == State.PROFILING ? saved.with(State.NEEDS_PROFILING, null) : saved;
        farms.put(farm.id(), farm);
        if (farm != saved) onChange.accept(farm);
        return farm;
    }

    public List<MarkedFarm> all() {
        return List.copyOf(farms.values());
    }

    private void validate(Cuboid mechanism, List<OutputContainer> outputs) {
        var blocks = new HashSet<BlockPosition>();
        for (var output : outputs) {
            for (var block : output.blocks()) {
                if (!blocks.add(block)) throw new IllegalArgumentException("Выходной контейнер выбран дважды.");
                if (!mechanism.min().worldId().equals(block.worldId())) {
                    throw new IllegalArgumentException("Механизм и выходной контейнер должны быть в одном мире.");
                }
                if (mechanism.contains(block)) {
                    throw new IllegalArgumentException("Выходной контейнер должен находиться вне механизма.");
                }
            }
        }
        for (var farm : farms.values()) {
            if (mechanism.sharesMechanismChunks(farm.mechanism())) {
                throw new IllegalArgumentException("Чанки механизма уже заняты другой фермой.");
            }
            for (var block : blocks) {
                if (farm.outputBlocks().anyMatch(block::equals)) {
                    throw new IllegalArgumentException("Выходной контейнер уже назначен другой ферме.");
                }
                if (farm.mechanism().contains(block)) {
                    throw new IllegalArgumentException("Выходной контейнер находится внутри другой фермы.");
                }
            }
            if (farm.outputBlocks().anyMatch(mechanism::contains)) {
                throw new IllegalArgumentException("Механизм включает выходной контейнер другой фермы.");
            }
        }
    }

    private void requireFreeName(UUID creatorId, String name, UUID except) {
        for (var farm : farms.values()) {
            if (farm.creatorId().equals(creatorId) && name.equals(farm.name()) && !farm.id().equals(except)) {
                throw new IllegalArgumentException("У вас уже есть ферма с именем " + name + ".");
            }
        }
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

    /**
     * Finds a farm by its case-sensitive name among the actor's own farms, or by UUID with the creator check.
     */
    public MarkedFarm require(FarmArguments.Reference reference, UUID actorId) {
        Objects.requireNonNull(actorId, "actorId");
        return switch (Objects.requireNonNull(reference, "reference")) {
            case FarmArguments.Reference.ById byId -> requireCreator(byId.id(), actorId);
            case FarmArguments.Reference.ByName byName -> farms.values().stream()
                    .filter(farm -> farm.creatorId().equals(actorId) && byName.name().equals(farm.name()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("У вас нет фермы с именем " + byName.name()
                            + ". Чтобы указать UUID, используйте " + FarmArguments.ID_FLAG + " <UUID>."));
        };
    }

    /** Gives the farm a new name, unique among the creator's farms. The state and profile are kept. */
    public MarkedFarm rename(UUID farmId, UUID actorId, String name) {
        var farm = requireCreator(farmId, actorId);
        requireFreeName(farm.creatorId(), FarmNames.requireValid(name), farm.id());
        var renamed = farm.withName(name);
        farms.put(farmId, renamed);
        onChange.accept(renamed);
        return renamed;
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

    /**
     * Snapshot recovery: the stasis manifest is authoritative for STASIS. A farm already loaded from storage
     * must have the same placement and keeps its name, which the manifest does not hold; a farm missing from
     * storage is registered unnamed under the usual placement rules. Any difference from the loaded state is
     * reported to the listener so storage catches up.
     */
    public void recoverStasis(MarkedFarm farm) {
        if (farm.state() != State.STASIS) throw new IllegalArgumentException("Ожидается STASIS.");
        var existing = farms.get(farm.id());
        if (existing == null) {
            validate(farm.mechanism(), farm.outputs());
        } else if (!existing.creatorId().equals(farm.creatorId())
                || !existing.mechanism().equals(farm.mechanism()) || !existing.outputs().equals(farm.outputs())) {
            throw new IllegalArgumentException("Снимок конфликтует с регистрацией фермы " + farm.id());
        }
        var recovered = farm.withName(existing == null ? null : existing.name());
        farms.put(recovered.id(), recovered);
        if (!recovered.equals(existing)) onChange.accept(recovered);
    }

    /** Drops any previous profile: it must never be reused. */
    public MarkedFarm markNeedsProfiling(UUID farmId) {
        return replace(farmId, State.NEEDS_PROFILING, null);
    }

    /**
     * A player changed {@code block}: a READY mechanism containing it no longer matches its profile, which is
     * dropped. Farms in other states are left as they are.
     *
     * @return the farms moved to NEEDS_PROFILING
     */
    public List<MarkedFarm> invalidateReady(BlockPosition block) {
        Objects.requireNonNull(block, "block");
        return farms.values().stream()
                .filter(farm -> farm.state() == State.READY && farm.mechanism().contains(block))
                .map(MarkedFarm::id).toList().stream()
                .map(this::markNeedsProfiling).toList();
    }

    /** Forgets the registration, freeing its placement and name. Saved data is the caller's to delete first. */
    public void remove(UUID farmId) {
        if (farms.remove(Objects.requireNonNull(farmId, "farmId")) == null) {
            throw new IllegalArgumentException("Ферма не найдена.");
        }
    }

    private MarkedFarm replace(UUID farmId, State state, ProductionProfile profile) {
        var farm = farms.get(Objects.requireNonNull(farmId, "farmId"));
        if (farm == null) throw new IllegalArgumentException("Ферма не найдена.");
        var updated = farm.with(state, profile);
        farms.put(farmId, updated);
        onChange.accept(updated);
        return updated;
    }

    public List<MarkedFarm> findByCreator(UUID creatorId) {
        Objects.requireNonNull(creatorId, "creatorId");
        return farms.values().stream().filter(farm -> farm.creatorId().equals(creatorId)).toList();
    }
}
