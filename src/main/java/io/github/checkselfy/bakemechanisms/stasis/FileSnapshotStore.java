package io.github.checkselfy.bakemechanisms.stasis;

import io.github.checkselfy.bakemechanisms.marking.BlockPosition;
import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.MarkedFarm;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.State;
import io.github.checkselfy.bakemechanisms.marking.OutputContainer;
import io.github.checkselfy.bakemechanisms.profiling.ProductionProfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** Snapshot-owned recovery manifest; does not persist the emulation state maintained by #8. */
public final class FileSnapshotStore implements SnapshotStore {
    private static final int MAX_BYTES = 128 * 1024 * 1024;
    private final Path farmsDirectory;
    private final String minecraftVersion;

    public FileSnapshotStore(Path farmsDirectory, String minecraftVersion) {
        this.farmsDirectory = farmsDirectory;
        this.minecraftVersion = minecraftVersion;
    }

    @Override public void save(SavedSnapshot snapshot) throws IOException {
        if (!minecraftVersion.equals("26.3")) throw new IOException("Снимки поддерживаются только на Paper 26.3.");
        var farm = snapshot.farm();
        var data = snapshot.data();
        if (data.length > MAX_BYTES) throw new IOException("Снимок превышает безопасный предел 128 MiB.");
        var directory = farmsDirectory.resolve(farm.id().toString());
        Files.createDirectories(directory);
        if (Files.exists(directory.resolve("stasis.yml"))) throw new IOException("Активный снимок уже существует.");
        var yaml = new YamlConfiguration();
        yaml.set("format-version", 1);
        yaml.set("minecraft-version", minecraftVersion);
        yaml.set("id", farm.id().toString());
        yaml.set("creator", farm.creatorId().toString());
        yaml.set("world", farm.mechanism().min().worldId().toString());
        yaml.set("min", coordinates(farm.mechanism().min()));
        yaml.set("max", coordinates(farm.mechanism().max()));
        yaml.set("output.type", farm.output().type().name());
        yaml.set("output.blocks", farm.output().blocks().stream().map(FileSnapshotStore::coordinates).sorted().toList());
        yaml.set("profile.useful-ticks", farm.profile().usefulTicks());
        farm.profile().ratesPer20Ticks().forEach((material, rate) -> yaml.set("profile.rates." + material.name(), rate));
        yaml.set("sha256", digest(data));
        yaml.set("binary-file", "snapshot.bin");
        atomicWrite(directory.resolve("snapshot.bin"), data);
        // Publication point: without this manifest, a file is never treated as an active snapshot.
        atomicWrite(directory.resolve("stasis.yml"), yaml.saveToString().getBytes(StandardCharsets.UTF_8));
    }

    @Override public List<SavedSnapshot> loadActive() throws IOException {
        var snapshots = new ArrayList<SavedSnapshot>();
        if (!Files.exists(farmsDirectory)) return List.of();
        try (var children = Files.list(farmsDirectory)) {
            for (var directory : children.filter(Files::isDirectory).sorted().toList()) {
                var manifest = directory.resolve("stasis.yml");
                if (Files.isRegularFile(manifest)) snapshots.add(read(directory, manifest));
            }
        }
        return List.copyOf(snapshots);
    }

    private SavedSnapshot read(Path directory, Path manifest) throws IOException {
        try {
            var yaml = new YamlConfiguration();
            yaml.load(manifest.toFile());
            if (yaml.getInt("format-version") != 1 || !minecraftVersion.equals("26.3")
                    || !minecraftVersion.equals(yaml.getString("minecraft-version"))) {
                throw new IOException("Несовместимая версия снимка: " + manifest);
            }
            var id = UUID.fromString(yaml.getString("id"));
            if (!directory.getFileName().toString().equals(id.toString())) throw new IOException("Неверный UUID снимка.");
            var world = UUID.fromString(yaml.getString("world"));
            var mechanism = new Cuboid(position(world, yaml.getString("min")), position(world, yaml.getString("max")));
            var blocks = new HashSet<BlockPosition>();
            for (var value : yaml.getStringList("output.blocks")) blocks.add(position(world, value));
            var output = new OutputContainer(OutputContainer.Type.valueOf(yaml.getString("output.type")), blocks);
            if (output.blocks().stream().anyMatch(mechanism::contains)) throw new IOException("Выход внутри снимка.");
            var rates = new HashMap<Material, Double>();
            var section = yaml.getConfigurationSection("profile.rates");
            if (section == null) throw new IOException("Профиль снимка отсутствует.");
            for (var name : section.getKeys(false)) {
                double rate = section.getDouble(name);
                if (!Double.isFinite(rate) || rate <= 0) throw new IOException("Некорректная скорость профиля.");
                rates.put(Material.valueOf(name), rate);
            }
            var farm = new MarkedFarm(id, UUID.fromString(yaml.getString("creator")), mechanism, output, State.STASIS,
                    new ProductionProfile(rates, yaml.getLong("profile.useful-ticks")));
            String binaryName = yaml.getString("binary-file", "blocks.nbt");
            if (!List.of("snapshot.bin", "blocks.nbt").contains(binaryName)) throw new IOException("Неверное имя снимка.");
            var binary = directory.resolve(binaryName);
            if (Files.size(binary) > MAX_BYTES) throw new IOException("Снимок слишком велик.");
            var data = Files.readAllBytes(binary);
            if (!digest(data).equals(yaml.getString("sha256"))) throw new IOException("Повреждён снимок " + id);
            return new SavedSnapshot(farm, data);
        } catch (InvalidConfigurationException | IllegalArgumentException | NullPointerException error) {
            throw new IOException("Не удалось прочитать снимок " + manifest, error);
        }
    }

    @Override public void deactivate(UUID farmId) throws IOException {
        Files.delete(farmsDirectory.resolve(farmId.toString()).resolve("stasis.yml"));
    }

    private static String coordinates(BlockPosition block) {
        return block.x() + "," + block.y() + "," + block.z();
    }

    private static BlockPosition position(UUID world, String value) {
        var parts = value.split(",", -1);
        if (parts.length != 3) throw new IllegalArgumentException("Неверные координаты.");
        return new BlockPosition(world, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    private static String digest(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void atomicWrite(Path path, byte[] data) throws IOException {
        var temporary = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, data);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
