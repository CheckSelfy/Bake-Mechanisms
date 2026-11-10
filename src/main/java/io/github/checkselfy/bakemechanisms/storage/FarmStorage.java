package io.github.checkselfy.bakemechanisms.storage;

import io.github.checkselfy.bakemechanisms.emulation.EmulationState;
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
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * One directory per farm under {@code root}: {@code farm.yml} holds human-readable metadata and the profile,
 * {@code emulation.dat} holds the binary emulation state (buffered items and fractional accumulators).
 * No database is used. Each file is replaced atomically where the file system allows it. Emulation state lives
 * only beside a profile: saving a farm without one drops it, so a stale buffer never meets a new profile.
 */
public final class FarmStorage {
    private static final String FARM_FILE = "farm.yml";
    private static final String EMULATION_FILE = "emulation.dat";
    private static final int FARM_FORMAT = 1;
    private static final int EMULATION_FORMAT = 1;
    private static final int EMULATION_MAGIC = 0x42414b45; // "BAKE"

    /** @param emulation present only while emulation state was saved for the farm */
    public record StoredFarm(MarkedFarm farm, Optional<EmulationState> emulation) {
        public StoredFarm {
            Objects.requireNonNull(farm, "farm");
            Objects.requireNonNull(emulation, "emulation");
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
            out.writeInt(state.buffer().size());
            for (var entry : sorted(state.buffer())) {
                out.writeUTF(entry.getKey().getKey().toString());
                out.writeInt(entry.getValue());
            }
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        write(directory(farmId).resolve(EMULATION_FILE), bytes.toByteArray());
    }

    public void deleteEmulation(UUID farmId) {
        try {
            Files.deleteIfExists(directory(farmId).resolve(EMULATION_FILE));
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
            } catch (IOException | InvalidConfigurationException | RuntimeException error) {
                throw new IllegalStateException("Не удалось прочитать данные фермы " + dir.getFileName() + ": "
                        + error.getMessage(), error);
            }
        }
        return farms;
    }

    private StoredFarm load(Path dir) throws IOException, InvalidConfigurationException {
        var yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(dir.resolve(FARM_FILE), StandardCharsets.UTF_8));
        if (yaml.getInt("format") != FARM_FORMAT) throw new IllegalArgumentException("неизвестный формат");
        var id = UUID.fromString(required(yaml, "id"));
        if (!dir.getFileName().toString().equals(id.toString())) {
            throw new IllegalArgumentException("каталог не совпадает с id " + id);
        }
        var world = UUID.fromString(required(yaml, "world"));
        var mechanism = new Cuboid(position(world, yaml.getIntegerList("mechanism.min")),
                position(world, yaml.getIntegerList("mechanism.max")));
        var blocks = new LinkedHashSet<BlockPosition>();
        for (var block : yaml.getList("output.blocks", List.of())) {
            if (!(block instanceof List<?> coordinates)) throw new IllegalArgumentException("неверный блок выходного контейнера");
            blocks.add(position(world, coordinates));
        }
        var output = new OutputContainer(OutputContainer.Type.valueOf(required(yaml, "output.type")), blocks);
        ProductionProfile profile = null;
        var profileSection = yaml.getConfigurationSection("profile");
        if (profileSection != null) profile = profile(profileSection);
        var farm = new MarkedFarm(id, UUID.fromString(required(yaml, "creator")), mechanism, output,
                State.valueOf(required(yaml, "state")), profile);
        var emulationFile = dir.resolve(EMULATION_FILE);
        if (!Files.exists(emulationFile)) return new StoredFarm(farm, Optional.empty());
        var emulation = emulation(Files.readAllBytes(emulationFile));
        var produced = profile == null ? Set.<Material>of() : profile.ratesPer20Ticks().keySet();
        if (!produced.containsAll(emulation.fractions().keySet()) || !produced.containsAll(emulation.buffer().keySet())) {
            throw new IllegalArgumentException("состояние эмуляции не соответствует профилю производства");
        }
        return new StoredFarm(farm, Optional.of(emulation));
    }

    private static ProductionProfile profile(ConfigurationSection section) {
        var rates = new EnumMap<Material, Double>(Material.class);
        var ratesSection = section.getConfigurationSection("rates-per-20-ticks");
        if (ratesSection == null) throw new IllegalArgumentException("профиль без скоростей");
        for (var key : ratesSection.getKeys(false)) {
            if (!ratesSection.isDouble(key) && !ratesSection.isInt(key)) {
                throw new IllegalArgumentException("неверная скорость " + key);
            }
            rates.put(material(key), ratesSection.getDouble(key));
        }
        if (!section.isLong("useful-ticks") && !section.isInt("useful-ticks")) {
            throw new IllegalArgumentException("нет полезного участка профиля");
        }
        return new ProductionProfile(rates, section.getLong("useful-ticks"));
    }

    private static EmulationState emulation(byte[] bytes) throws IOException {
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != EMULATION_MAGIC || in.readInt() != EMULATION_FORMAT) {
                throw new IllegalArgumentException("неизвестный формат состояния эмуляции");
            }
            var fractions = new EnumMap<Material, BigDecimal>(Material.class);
            for (int i = in.readInt(); i > 0; i--) fractions.put(material(in.readUTF()), new BigDecimal(in.readUTF()));
            var buffer = new EnumMap<Material, Integer>(Material.class);
            for (int i = in.readInt(); i > 0; i--) buffer.put(material(in.readUTF()), in.readInt());
            if (in.read() != -1) throw new IllegalArgumentException("лишние данные в состоянии эмуляции");
            return new EmulationState(fractions, buffer);
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

    private static BlockPosition position(UUID world, List<?> coordinates) {
        if (coordinates.size() != 3 || !coordinates.stream().allMatch(Integer.class::isInstance)) {
            throw new IllegalArgumentException("неверные координаты " + coordinates);
        }
        return new BlockPosition(world, (Integer) coordinates.get(0), (Integer) coordinates.get(1),
                (Integer) coordinates.get(2));
    }

    private static Material material(String key) {
        var material = Material.matchMaterial(key);
        if (material == null || material.isLegacy()) throw new IllegalArgumentException("неизвестный предмет " + key);
        return material;
    }

    private static String required(ConfigurationSection section, String path) {
        var value = section.getString(path);
        if (value == null) throw new IllegalArgumentException("нет поля " + path);
        return value;
    }
}
