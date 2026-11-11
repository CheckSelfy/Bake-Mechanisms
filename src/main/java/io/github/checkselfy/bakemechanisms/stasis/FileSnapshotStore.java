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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** Snapshot-owned recovery manifest; does not persist the emulation state maintained by #8. */
public final class FileSnapshotStore implements SnapshotStore {
    private static final int MAX_BYTES = 128 * 1024 * 1024;
    /** Format 3 persists the filler; formats 1/2 used bedrock. Format 1 had a single output. */
    private static final int FORMAT = 3;
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
        yaml.set("format-version", FORMAT);
        yaml.set("minecraft-version", minecraftVersion);
        yaml.set("id", farm.id().toString());
        yaml.set("creator", farm.creatorId().toString());
        yaml.set("world", farm.mechanism().min().worldId().toString());
        yaml.set("min", coordinates(farm.mechanism().min()));
        yaml.set("max", coordinates(farm.mechanism().max()));
        var outputs = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < farm.outputs().size(); i++) {
            var output = new LinkedHashMap<String, Object>();
            output.put("type", farm.outputs().get(i).type().name());
            output.put("blocks", farm.outputs().get(i).blocks().stream().map(FileSnapshotStore::coordinates).sorted()
                    .toList());
            var rates = new TreeMap<String, Double>();
            farm.profile().outputs().get(i).forEach((material, rate) -> rates.put(material.name(), rate));
            output.put("rates", rates);
            outputs.add(output);
        }
        yaml.set("outputs", outputs);
        yaml.set("profile.useful-ticks", farm.profile().usefulTicks());
        yaml.set("sha256", digest(data));
        yaml.set("binary-file", "snapshot.bin");
        writeFiller(yaml,snapshot.filler());
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
            int format = yaml.getInt("format-version");
            if ((format < 1 || format > FORMAT) || !minecraftVersion.equals("26.3")
                    || !minecraftVersion.equals(yaml.getString("minecraft-version"))) {
                throw new IOException("Несовместимая версия снимка: " + manifest);
            }
            var id = UUID.fromString(yaml.getString("id"));
            if (!directory.getFileName().toString().equals(id.toString())) throw new IOException("Неверный UUID снимка.");
            var world = UUID.fromString(yaml.getString("world"));
            var mechanism = new Cuboid(position(world, yaml.getString("min")), position(world, yaml.getString("max")));
            var outputs = new ArrayList<OutputContainer>();
            var rates = new ArrayList<Map<Material, Double>>();
            if (format == 1) {
                // A single output, with the profile's rates in a separate section.
                outputs.add(output(world, yaml.getString("output.type"), yaml.getStringList("output.blocks")));
                var section = yaml.getConfigurationSection("profile.rates");
                if (section == null) throw new IOException("Профиль снимка отсутствует.");
                rates.add(rates(section.getValues(false)));
            } else {
                for (var entry : yaml.getMapList("outputs")) {
                    if (!(entry.get("blocks") instanceof List<?> blocks) || !(entry.get("rates") instanceof Map<?, ?> map)) {
                        throw new IOException("Неверное описание выходного контейнера снимка.");
                    }
                    outputs.add(output(world, String.valueOf(entry.get("type")),
                            blocks.stream().map(String::valueOf).toList()));
                    rates.add(rates(map));
                }
            }
            for (var output : outputs) {
                if (output.blocks().stream().anyMatch(mechanism::contains)) throw new IOException("Выход внутри снимка.");
            }
            var farm = new MarkedFarm(id, UUID.fromString(yaml.getString("creator")), null, mechanism, outputs,
                    State.STASIS, new ProductionProfile(rates, yaml.getLong("profile.useful-ticks")));
            String binaryName = yaml.getString("binary-file", "blocks.nbt");
            if (!List.of("snapshot.bin", "blocks.nbt").contains(binaryName)) throw new IOException("Неверное имя снимка.");
            var binary = directory.resolve(binaryName);
            if (Files.size(binary) > MAX_BYTES) throw new IOException("Снимок слишком велик.");
            var data = Files.readAllBytes(binary);
            if (!digest(data).equals(yaml.getString("sha256"))) throw new IOException("Повреждён снимок " + id);
            return new SavedSnapshot(farm, data,format < 3 ? FillerStyle.LEGACY : readFiller(yaml));
        } catch (InvalidConfigurationException | IllegalArgumentException | NullPointerException error) {
            throw new IOException("Не удалось прочитать снимок " + manifest, error);
        }
    }

    private static void writeFiller(YamlConfiguration yaml,FillerStyle style) {
        yaml.set("filler.algorithm-version",style.version());
        yaml.set("filler.front",style.front().name());
        yaml.set("filler.pattern",style.pattern().name());
        yaml.set("filler.patch-size",style.patchSize());
        var p=style.palette();
        yaml.set("filler.palette.shell",p.shell().name()); yaml.set("filler.palette.shade",p.shade().name());
        yaml.set("filler.palette.core",p.core().name()); yaml.set("filler.palette.accent",p.accent().name());
        yaml.set("filler.palette.face",p.face().name()); yaml.set("filler.palette.face-glass",p.faceGlass().name());
    }

    private static FillerStyle readFiller(YamlConfiguration yaml) throws IOException {
        var section=yaml.getConfigurationSection("filler");
        if(section==null || !section.isInt("algorithm-version") || !section.isInt("patch-size"))
            throw new IOException("Описание заполнителя отсутствует или повреждено.");
        for(var key:List.of("front","pattern","palette.shell","palette.shade","palette.core",
                "palette.accent","palette.face","palette.face-glass"))
            if(!section.isString(key)) throw new IOException("Описание заполнителя не содержит " + key);
        var style=FillerStyle.fromConfig(section);
        if(style.front()==FillerStyle.Front.AUTO) throw new IOException("Направление лица заполнителя не сохранено.");
        return new FillerStyle(section.getInt("algorithm-version"),style.front(),style.pattern(),style.patchSize(),style.palette());
    }

    @Override public void deactivate(UUID farmId) throws IOException {
        Files.delete(farmsDirectory.resolve(farmId.toString()).resolve("stasis.yml"));
    }

    private static OutputContainer output(UUID world, String type, List<String> coordinates) {
        var blocks = new HashSet<BlockPosition>();
        for (var value : coordinates) blocks.add(position(world, value));
        return new OutputContainer(OutputContainer.Type.valueOf(type), blocks);
    }

    private static Map<Material, Double> rates(Map<?, ?> values) throws IOException {
        var rates = new HashMap<Material, Double>();
        for (var entry : values.entrySet()) {
            if (!(entry.getValue() instanceof Number number)) throw new IOException("Некорректная скорость профиля.");
            double rate = number.doubleValue();
            if (!Double.isFinite(rate) || rate <= 0) throw new IOException("Некорректная скорость профиля.");
            rates.put(Material.valueOf(String.valueOf(entry.getKey())), rate);
        }
        return rates;
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
