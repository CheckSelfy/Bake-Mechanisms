package io.github.checkselfy.bakemechanisms.storage;

import io.github.checkselfy.bakemechanisms.emulation.EmulationState;
import io.github.checkselfy.bakemechanisms.emulation.EmulationStore;
import io.github.checkselfy.bakemechanisms.marking.BlockPosition;
import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.MarkedFarm;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.State;
import io.github.checkselfy.bakemechanisms.marking.OutputContainer;
import io.github.checkselfy.bakemechanisms.profiling.ProductionProfile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * One directory per farm under {@code root}: {@code farm.yml} holds human-readable metadata and the profile,
 * {@code emulation.dat} holds the binary emulation state (buffered items and fractional accumulators), and
 * {@code drain.dat} the leftover buffer of a restored farm. No database is used. Each file is replaced atomically
 * where the file system allows it. Emulation state lives only beside a profile: saving a farm without one drops
 * it, so a stale buffer never meets a new profile. The leftover buffer needs no profile and stays until delivered.
 */
public final class FarmStorage implements EmulationStore {
    private static final String FARM_FILE = "farm.yml";
    private static final String EMULATION_FILE = "emulation.dat";
    private static final String DRAIN_FILE = "drain.dat";
    /** Owned by the snapshot store, which shares the farm directory; its presence marks an active snapshot. */
    private static final String ACTIVE_SNAPSHOT_FILE = "stasis.yml";
    private static final int DRAIN_FORMAT = 1;
    private static final int DRAIN_MAGIC = 0x4452414e; // "DRAN"
    private static final int FARM_FORMAT = 1;
    private static final int EMULATION_FORMAT = 1;
    private static final int EMULATION_MAGIC = 0x42414b45; // "BAKE"

    /**
     * @param emulation present only while emulation state was saved for the farm
     * @param drain leftover buffer of a restored farm, empty when nothing waits for delivery
     */
    public record StoredFarm(MarkedFarm farm, Optional<EmulationState> emulation, Map<Material, Integer> drain) {
        public StoredFarm {
            Objects.requireNonNull(farm, "farm");
            Objects.requireNonNull(emulation, "emulation");
            drain = Map.copyOf(Objects.requireNonNull(drain, "drain"));
        }

        public StoredFarm(MarkedFarm farm, Optional<EmulationState> emulation) {
            this(farm, emulation, Map.of());
        }
    }

    private final Path root;

    public FarmStorage(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    /** @throws UncheckedIOException when the file cannot be written; the previous file then stays intact */
    public void save(MarkedFarm farm) {
        var yaml = new YamlConfiguration();
        yaml.set("format", FARM_FORMAT);
        yaml.set("id", farm.id().toString());
        yaml.set("creator", farm.creatorId().toString());
        yaml.set("state", farm.state().name());
        var mechanism = farm.mechanism();
        yaml.set("world", mechanism.min().worldId().toString());
        yaml.set("mechanism.min", coordinates(mechanism.min()));
        yaml.set("mechanism.max", coordinates(mechanism.max()));
        yaml.set("output.type", farm.output().type().name());
        yaml.set("output.blocks", farm.output().blocks().stream()
                .sorted(Comparator.comparingInt(BlockPosition::x).thenComparingInt(BlockPosition::y)
                        .thenComparingInt(BlockPosition::z))
                .map(FarmStorage::coordinates).toList());
        if (farm.profile() != null) {
            yaml.set("profile.useful-ticks", farm.profile().usefulTicks());
            // Key order is stable for readers; Material.getKey() avoids path separators in YAML keys.
            var rates = yaml.createSection("profile.rates-per-20-ticks");
            farm.profile().ratesPer20Ticks().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(rate -> rates.set(rate.getKey().getKey().toString(), rate.getValue()));
        }
        write(directory(farm.id()).resolve(FARM_FILE), yaml.saveToString().getBytes(StandardCharsets.UTF_8));
        if (farm.profile() == null) deleteEmulation(farm.id());
    }

    /** @throws UncheckedIOException when the file cannot be written; the previous file then stays intact */
    @Override
    public void saveEmulation(UUID farmId, EmulationState state) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(EMULATION_MAGIC);
            out.writeInt(EMULATION_FORMAT);
            out.writeInt(state.fractions().size());
            for (var entry : sorted(state.fractions())) {
                out.writeUTF(entry.getKey().getKey().toString());
                out.writeUTF(entry.getValue().toString());
            }
            writeBuffer(out, state.buffer());
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        write(directory(farmId).resolve(EMULATION_FILE), bytes.toByteArray());
    }

    /** @throws UncheckedIOException when the file cannot be written; the previous file then stays intact */
    @Override
    public void saveDrain(UUID farmId, Map<Material, Integer> buffer) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(DRAIN_MAGIC);
            out.writeInt(DRAIN_FORMAT);
            writeBuffer(out, buffer);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        write(directory(farmId).resolve(DRAIN_FILE), bytes.toByteArray());
    }

    @Override
    public void deleteDrain(UUID farmId) {
        try {
            Files.deleteIfExists(directory(farmId).resolve(DRAIN_FILE));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static void writeBuffer(DataOutputStream out, Map<Material, Integer> buffer) throws IOException {
        out.writeInt(buffer.size());
        for (var entry : sorted(buffer)) {
            out.writeUTF(entry.getKey().getKey().toString());
            out.writeInt(entry.getValue());
        }
    }

    public void deleteEmulation(UUID farmId) {
        try {
            Files.deleteIfExists(directory(farmId).resolve(EMULATION_FILE));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /**
     * Deletes the farm's directory with every file in it, including an inactive snapshot kept for diagnostics.
     * {@code farm.yml} goes last, so a failed deletion leaves a loadable farm that can be deleted again.
     *
     * @throws IllegalStateException when the farm has an active snapshot ({@value #ACTIVE_SNAPSHOT_FILE}); nothing
     *                               is deleted, because that snapshot may be the only copy of the original mechanism
     * @throws UncheckedIOException  when a file cannot be deleted
     */
    public void delete(UUID farmId) {
        var dir = directory(farmId);
        if (!Files.isDirectory(dir)) return;
        if (Files.exists(dir.resolve(ACTIVE_SNAPSHOT_FILE))) {
            throw new IllegalStateException("Farm " + farmId + " has an active snapshot and cannot be deleted.");
        }
        try {
            List<Path> files;
            try (var entries = Files.list(dir)) {
                files = entries.sorted(Comparator.comparing(file -> file.getFileName().toString().equals(FARM_FILE)))
                        .toList();
            }
            for (var file : files) Files.delete(file);
            Files.delete(dir);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /**
     * Reads every saved farm. A farm that cannot be read fails the whole load rather than being skipped, so
     * its data is never silently lost or overwritten.
     *
     * @throws IllegalStateException naming the farm directory that could not be read
     */
    public List<StoredFarm> loadAll() {
        if (!Files.isDirectory(root)) return List.of();
        List<Path> dirs;
        try (var entries = Files.list(root)) {
            // A directory without farm.yml only holds a first save that never completed.
            dirs = entries.filter(dir -> Files.isRegularFile(dir.resolve(FARM_FILE))).sorted().toList();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        var farms = new ArrayList<StoredFarm>();
        for (var dir : dirs) {
            try {
                farms.add(load(dir));
            } catch (InvalidFile error) {
                throw new IllegalStateException("Cannot load farm " + dir.getFileName() + " from " + error.file
                        + ": " + error.getMessage(), error.getCause());
            } catch (IOException error) {
                throw new IllegalStateException("Cannot load farm " + dir.getFileName() + " from " + dir
                        + ": " + error, error);
            }
        }
        return farms;
    }

    /**
     * Load errors are read by admins in the server console, so their text is English: Cyrillic is garbled by
     * consoles that do not use UTF-8. Domain validation messages stay in the cause.
     */
    private static final class InvalidFile extends Exception {
        final Path file;

        InvalidFile(Path file, String reason, Throwable cause) {
            super(reason, cause);
            this.file = file;
        }

        InvalidFile(Path file, String reason) {
            this(file, reason, null);
        }
    }

    private interface Reader<T> {
        T read() throws InvalidFile;
    }

    /** Reads one value; any other failure, including domain validation, becomes {@code invalid <what>}. */
    private static <T> T read(Path file, String what, Reader<T> reader) throws InvalidFile {
        try {
            return reader.read();
        } catch (RuntimeException error) {
            throw new InvalidFile(file, "invalid " + what, error);
        }
    }

    private StoredFarm load(Path dir) throws IOException, InvalidFile {
        var file = dir.resolve(FARM_FILE);
        var yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException error) {
            throw new InvalidFile(file, "not valid YAML", error);
        }
        if (yaml.getInt("format") != FARM_FORMAT) {
            throw new InvalidFile(file, "unsupported format " + yaml.get("format") + ", expected " + FARM_FORMAT);
        }
        var id = read(file, "id", () -> UUID.fromString(required(file, yaml, "id")));
        if (!dir.getFileName().toString().equals(id.toString())) {
            throw new InvalidFile(file, "id " + id + " does not match its directory name");
        }
        var creator = read(file, "creator", () -> UUID.fromString(required(file, yaml, "creator")));
        var state = read(file, "state", () -> State.valueOf(required(file, yaml, "state")));
        var world = read(file, "world", () -> UUID.fromString(required(file, yaml, "world")));
        var mechanism = read(file, "mechanism", () -> new Cuboid(
                position(file, world, "mechanism.min", yaml.getList("mechanism.min", List.of())),
                position(file, world, "mechanism.max", yaml.getList("mechanism.max", List.of()))));
        var output = read(file, "output container", () -> {
            var blocks = new LinkedHashSet<BlockPosition>();
            for (var block : yaml.getList("output.blocks", List.of())) {
                if (!(block instanceof List<?> coordinates)) {
                    throw new InvalidFile(file, "output.blocks must hold [x, y, z] lists");
                }
                blocks.add(position(file, world, "output.blocks", coordinates));
            }
            return new OutputContainer(OutputContainer.Type.valueOf(required(file, yaml, "output.type")), blocks);
        });
        var profileSection = yaml.getConfigurationSection("profile");
        boolean profiled = state == State.READY || state == State.STASIS;
        if (profiled && profileSection == null) {
            throw new InvalidFile(file, "state " + state + " requires a profile");
        }
        if (!profiled && profileSection != null) {
            throw new InvalidFile(file, "state " + state + " must not have a profile (only READY and STASIS have one)");
        }
        var profile = profileSection == null ? null : read(file, "profile", () -> profile(file, profileSection));
        var farm = new MarkedFarm(id, creator, mechanism, output, state, profile);

        Map<Material, Integer> drain = Map.of();
        var drainFile = dir.resolve(DRAIN_FILE);
        if (Files.exists(drainFile)) {
            var drainBytes = Files.readAllBytes(drainFile);
            drain = read(drainFile, "leftover buffer", () -> drain(drainFile, drainBytes));
        }
        var emulationFile = dir.resolve(EMULATION_FILE);
        if (!Files.exists(emulationFile)) return new StoredFarm(farm, Optional.empty(), drain);
        if (profile == null) throw new InvalidFile(emulationFile, "emulation state without a profile");
        var bytes = Files.readAllBytes(emulationFile);
        var emulation = read(emulationFile, "emulation state", () -> emulation(emulationFile, bytes));
        var produced = profile.ratesPer20Ticks().keySet();
        if (!produced.containsAll(emulation.fractions().keySet()) || !produced.containsAll(emulation.buffer().keySet())) {
            throw new InvalidFile(emulationFile, "emulation state holds items the profile does not produce");
        }
        return new StoredFarm(farm, Optional.of(emulation), drain);
    }

    private static Map<Material, Integer> drain(Path file, byte[] bytes) throws InvalidFile {
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != DRAIN_MAGIC) throw new InvalidFile(file, "not a leftover buffer file");
            int format = in.readInt();
            if (format != DRAIN_FORMAT) {
                throw new InvalidFile(file, "unsupported format " + format + ", expected " + DRAIN_FORMAT);
            }
            var buffer = readBuffer(file, in);
            if (buffer.values().stream().anyMatch(amount -> amount < 1)) {
                throw new InvalidFile(file, "item counts must be positive");
            }
            if (in.read() != -1) throw new InvalidFile(file, "unexpected trailing data");
            return buffer;
        } catch (IOException error) {
            throw new InvalidFile(file, "truncated or unreadable", error);
        }
    }

    private static Map<Material, Integer> readBuffer(Path file, DataInputStream in)
            throws IOException, InvalidFile {
        var buffer = new EnumMap<Material, Integer>(Material.class);
        for (int i = in.readInt(); i > 0; i--) buffer.put(material(file, in.readUTF()), in.readInt());
        return buffer;
    }

    private static ProductionProfile profile(Path file, ConfigurationSection section) throws InvalidFile {
        var rates = new EnumMap<Material, Double>(Material.class);
        var ratesSection = section.getConfigurationSection("rates-per-20-ticks");
        if (ratesSection == null) throw new InvalidFile(file, "profile.rates-per-20-ticks is missing");
        for (var key : ratesSection.getKeys(false)) {
            if (!ratesSection.isDouble(key) && !ratesSection.isInt(key)) {
                throw new InvalidFile(file, "rate of " + key + " is not a number");
            }
            rates.put(material(file, key), ratesSection.getDouble(key));
        }
        if (!section.isLong("useful-ticks") && !section.isInt("useful-ticks")) {
            throw new InvalidFile(file, "profile.useful-ticks is missing or not a number");
        }
        return new ProductionProfile(rates, section.getLong("useful-ticks"));
    }

    private static EmulationState emulation(Path file, byte[] bytes) throws InvalidFile {
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != EMULATION_MAGIC) throw new InvalidFile(file, "not an emulation state file");
            int format = in.readInt();
            if (format != EMULATION_FORMAT) {
                throw new InvalidFile(file, "unsupported format " + format + ", expected " + EMULATION_FORMAT);
            }
            var fractions = new EnumMap<Material, BigDecimal>(Material.class);
            for (int i = in.readInt(); i > 0; i--) {
                fractions.put(material(file, in.readUTF()), new BigDecimal(in.readUTF()));
            }
            var buffer = readBuffer(file, in);
            if (in.read() != -1) throw new InvalidFile(file, "unexpected trailing data");
            return new EmulationState(fractions, buffer);
        } catch (IOException error) {
            throw new InvalidFile(file, "truncated or unreadable", error);
        }
    }

    private Path directory(UUID farmId) {
        return root.resolve(Objects.requireNonNull(farmId, "farmId").toString());
    }

    private static void write(Path file, byte[] content) {
        try {
            Files.createDirectories(file.getParent());
            var temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temporary, content);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static <V> List<Map.Entry<Material, V>> sorted(Map<Material, V> map) {
        return map.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
    }

    private static List<Integer> coordinates(BlockPosition block) {
        return List.of(block.x(), block.y(), block.z());
    }

    private static BlockPosition position(Path file, UUID world, String path, List<?> coordinates)
            throws InvalidFile {
        if (coordinates.size() != 3 || !coordinates.stream().allMatch(Integer.class::isInstance)) {
            throw new InvalidFile(file, path + " must be [x, y, z] integers, found " + coordinates);
        }
        return new BlockPosition(world, (Integer) coordinates.get(0), (Integer) coordinates.get(1),
                (Integer) coordinates.get(2));
    }

    private static Material material(Path file, String key) throws InvalidFile {
        var material = Material.matchMaterial(key);
        if (material == null || material.isLegacy()) throw new InvalidFile(file, "unknown item " + key);
        return material;
    }

    private static String required(Path file, ConfigurationSection section, String path) throws InvalidFile {
        var value = section.getString(path);
        if (value == null) throw new InvalidFile(file, path + " is missing");
        return value;
    }
}
