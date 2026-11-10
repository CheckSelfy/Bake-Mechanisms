package io.github.checkselfy.bakemechanisms.fixtures;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Container;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Test-server-only builder. No dependency on, or access to, production internals. */
public final class FixturePlugin extends JavaPlugin {
    private static final long SEED = 20261110L;
    private final List<Farm> farms = new ArrayList<>();
    private final List<Chunk> held = new ArrayList<>();
    private final File manifest = new File(getDataFolder(), "manifest.yml");
    private int observations;

    private record Farm(String id, World world, int x, int z, boolean entities) {
        org.bukkit.block.Block block(int dx, int y, int dz) {
            return world.getBlockAt(x + dx, y, z + dz);
        }
        Container output() { return (Container) block(16, 64, 7).getState(); }
        String location() {
            return id + " world=" + world.getName() + " min=" + x + ",63," + z
                    + " max=" + (x + 15) + ",70," + (z + 15)
                    + " output=" + (x + 16) + ",64," + (z + 7);
        }
    }

    @Override public void onEnable() {
        Objects.requireNonNull(getCommand("bakefixtures")).setExecutor(this);
        var saved = YamlConfiguration.loadConfiguration(manifest);
        for (String id : saved.getStringList("ids")) {
            String path = "farms." + id;
            String name = Objects.requireNonNull(saved.getString(path + ".world"));
            File folder = dimensionFolder(name);
            if (!folder.isDirectory()) {
                throw new IllegalStateException("Missing fixture world: " + name);
            }
            World loaded = world(name);
            if (!loaded.getUID().toString().equals(saved.getString(path + ".world-uuid"))) {
                throw new IllegalStateException("Fixture world UUID changed: " + name);
            }
            farms.add(new Farm(id, loaded, saved.getInt(path + ".x"),
                    saved.getInt(path + ".z"), saved.getBoolean(path + ".entities")));
        }
        getLogger().info("FIXTURES_READY farms=" + farms.size());
    }

    @Override public void onDisable() { release(); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("bakefixtures.manage")) return true;
        try {
            if (args.length == 2 && args[0].equals("create") && args[1].equals("correctness")) {
                requireNew("bake_blocks");
                requireNew("bake_entities");
                build(new Farm("blocks", world("bake_blocks"), 0, 0, false));
                build(new Farm("entities", world("bake_entities"), 0, 0, true));
                saveManifest();
                hold();
                reply(sender, "FIXTURES_CREATED correctness farms=2");
            } else if (args.length == 3 && args[0].equals("create") && args[1].equals("performance")) {
                int count = Integer.parseInt(args[2]);
                if (count < 1 || count > 100) throw new IllegalArgumentException("Farm count must be 1..100");
                requireNew("bake_performance");
                World world = world("bake_performance");
                int columns = (int) Math.ceil(Math.sqrt(count));
                for (int i = 0; i < count; i++) {
                    build(new Farm("perf%03d".formatted(i + 1), world,
                            (i % columns) * 48, (i / columns) * 48, false));
                }
                int rows = (count + columns - 1) / columns;
                world.setSpawnLocation((columns - 1) * 24 + 8, 65, (rows - 1) * 24 + 8);
                saveManifest();
                hold();
                reply(sender, "FIXTURES_CREATED performance farms=" + count);
            } else if (args.length == 1 && args[0].equals("list")) {
                for (Farm farm : farms) reply(sender, farm.location());
            } else if (args.length == 1 && args[0].equals("hold")) {
                hold();
                reply(sender, "FIXTURES_HELD chunks=" + held.size());
            } else if (args.length == 1 && args[0].equals("release")) {
                release();
                reply(sender, "FIXTURES_RELEASED");
            } else if (args.length == 1 && args[0].equals("check")) {
                check();
                reply(sender, "FIXTURES_CHECK_OK farms=" + farms.size());
            } else if (args.length == 3 && args[0].equals("observe")) {
                int ticks = Integer.parseInt(args[2]);
                if (ticks < 20 || ticks > 72000) throw new IllegalArgumentException("Ticks must be 20..72000");
                List<Farm> selected = args[1].equals("all") ? List.copyOf(farms) : List.of(find(args[1]));
                if (selected.isEmpty()) throw new IllegalArgumentException("No farms created");
                observe(sender, selected, ticks);
            } else if (args.length == 2 && args[0].equals("probe")) {
                Farm farm = find(args[1]);
                if (farm.entities()) throw new IllegalArgumentException("Probe is for cactus fixtures only");
                // A deliberately injected growth event, not evidence of natural production.
                for (int x : new int[] {2, 4}) for (int z : new int[] {2, 4}) {
                    if (farm.block(x, 66, z).getType() != Material.CACTUS) {
                        throw new IllegalArgumentException("Original cactus missing; do not probe in stasis");
                    }
                    farm.block(x + 1, 67, z).setType(Material.AIR, true);
                    farm.block(x, 67, z).setType(Material.CACTUS, true);
                    farm.block(x + 1, 67, z).setType(Material.IRON_BARS, true);
                }
                reply(sender, "FIXTURES_PROBE_INJECTED " + farm.id() + " cactus=4; not a natural production test");
            } else if (args.length == 2 && args[0].equals("visit") && sender instanceof Player player) {
                Farm farm = find(args[1]);
                player.teleport(new Location(farm.world(), farm.x() + 24.5, 65, farm.z() + 24.5));
            } else {
                sender.sendMessage(command.getUsage());
            }
        } catch (IllegalArgumentException | IllegalStateException | IOException error) {
            reply(sender, "FIXTURES_ERROR " + error.getMessage());
        }
        return true;
    }

    private void requireNew(String name) {
        if (Bukkit.getWorld(name) != null || dimensionFolder(name).exists()) {
            throw new IllegalArgumentException("World already exists: " + name + "; use a fresh test-server directory to reset");
        }
    }

    private File dimensionFolder(String name) {
        // Paper 26.3 stores custom dimensions alongside the overworld under one level root.
        return new File(Bukkit.getWorlds().getFirst().getWorldFolder().getParentFile(), name);
    }

    private World world(String name) {
        World existing = Bukkit.getWorld(name);
        if (existing != null) return existing;
        World result = Objects.requireNonNull(new WorldCreator(name).seed(SEED).generateStructures(false)
                .generator(new ChunkGenerator() {
                    @Override public void generateNoise(WorldInfo info, Random random, int chunkX,
                                                         int chunkZ, ChunkData data) {
                        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                            data.setBlock(x, 63, z, Material.STONE);
                        }
                    }
                }).createWorld());
        result.setGameRule(GameRules.ADVANCE_TIME, false);
        result.setGameRule(GameRules.ADVANCE_WEATHER, false);
        result.setGameRule(GameRules.SPAWN_MOBS, false);
        result.setGameRule(GameRules.RANDOM_TICK_SPEED, 3);
        result.setTime(6000);
        result.setSpawnLocation(24, 65, 24);
        return result;
    }

    private void build(Farm farm) {
        // All productive blocks, transfer hoppers and entities stay in one mechanism chunk.
        // Only the final barrel is outside, in its neighbouring chunk.
        farm.block(16, 64, 7).setType(Material.BARREL, false);
        if (!farm.id().startsWith("perf")) {
            // A disconnected internal inventory makes snapshot comparison deterministic.
            farm.block(0, 65, 0).setType(Material.BARREL, false);
            var marker = new ItemStack(Material.STONE, 3);
            marker.editMeta(meta -> meta.displayName(Component.text("snapshot-marker")));
            ((Container) farm.block(0, 65, 0).getState()).getInventory().setItem(0, marker);
        }
        if (farm.entities()) {
            for (int x = 3; x <= 8; x++) for (int z = 4; z <= 10; z++) {
                for (int y = 65; y <= 69; y++) {
                    if (x == 3 || x == 8 || z == 4 || z == 10 || y == 69) {
                        farm.block(x, y, z).setType(Material.GLASS, false);
                    }
                }
            }
            for (int x = 4; x <= 7; x++) for (int z = 5; z <= 9; z++) {
                hopper(farm, x, z, x < 7 ? BlockFace.EAST : towardRow(z));
            }
            for (int x = 8; x <= 15; x++) hopper(farm, x, 7, BlockFace.EAST);
            for (int i = 0; i < 8; i++) {
                int index = i;
                farm.world().spawn(new Location(farm.world(), farm.x() + 4.5 + i % 4, 65,
                        farm.z() + 6.5 + i / 4), Chicken.class, chicken -> {
                    chicken.setAdult();
                    chicken.setPersistent(true);
                    chicken.setRemoveWhenFarAway(false);
                    chicken.customName(Component.text("fixture-hen-" + index));
                    // Known initial condition; subsequent egg timers are vanilla random values.
                    chicken.setEggLayTime(200 + index * 40);
                });
            }
        } else {
            for (int x = 1; x <= 15; x++) for (int z = 1; z <= 15; z++) {
                hopper(farm, x, z, x < 15 ? BlockFace.EAST : towardRow(z));
            }
            for (int x = 2; x <= 14; x += 2) for (int z = 2; z <= 14; z += 2) {
                farm.block(x, 65, z).setType(Material.SAND, false);
                farm.block(x, 66, z).setType(Material.CACTUS, false);
                Ageable age = (Ageable) farm.block(x, 66, z).getBlockData();
                age.setAge((x * 7 + z / 2) % (age.getMaximumAge() + 1));
                farm.block(x, 66, z).setBlockData(age, false);
                farm.block(x + 1, 67, z).setType(Material.IRON_BARS, false);
            }
        }
        farms.add(farm);
    }

    private static BlockFace towardRow(int z) {
        return z < 7 ? BlockFace.SOUTH : z > 7 ? BlockFace.NORTH : BlockFace.EAST;
    }

    private void hopper(Farm farm, int x, int z, BlockFace facing) {
        var block = farm.block(x, 64, z);
        block.setType(Material.HOPPER, false);
        Directional data = (Directional) block.getBlockData();
        data.setFacing(facing);
        block.setBlockData(data, false);
    }

    private Farm find(String id) {
        return farms.stream().filter(farm -> farm.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown fixture: " + id));
    }

    private void hold() {
        for (Farm farm : farms) for (int dx = 0; dx <= 1; dx++) {
            Chunk chunk = farm.world().getChunkAt((farm.x() >> 4) + dx, farm.z() >> 4);
            if (!held.contains(chunk)) {
                chunk.addPluginChunkTicket(this);
                held.add(chunk);
            }
        }
    }

    private void release() {
        for (Chunk chunk : held) chunk.removePluginChunkTicket(this);
        held.clear();
    }

    private void check() {
        if (farms.isEmpty()) throw new IllegalArgumentException("No farms created");
        for (Farm farm : farms) {
            if (farm.x() % 16 != 0 || farm.z() % 16 != 0) throw new IllegalStateException("Unaligned farm");
            if (farm.block(16, 64, 7).getType() != Material.BARREL) throw new IllegalStateException("Missing output: " + farm.id());
            if (farm.block(15, 64, 7).getType() != Material.HOPPER) throw new IllegalStateException("Missing transfer: " + farm.id());
            if (!farm.id().startsWith("perf")) {
                if (!(farm.block(0, 65, 0).getState() instanceof Container container)) {
                    throw new IllegalStateException("Missing internal marker barrel: " + farm.id());
                }
                ItemStack marker = container.getInventory().getItem(0);
                if (marker == null || marker.getType() != Material.STONE || marker.getAmount() != 3
                        || !Component.text("snapshot-marker").equals(marker.getItemMeta().displayName())) {
                    throw new IllegalStateException("Internal snapshot marker changed: " + farm.id());
                }
            }
            // Follow actual Paper BlockData, rather than just counting generated hoppers.
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                var block = farm.block(x, 64, z);
                if (block.getType() != Material.HOPPER) continue;
                var visited = new HashSet<org.bukkit.block.Block>();
                while (!block.equals(farm.block(16, 64, 7))) {
                    if (block.getType() != Material.HOPPER || !visited.add(block)) {
                        throw new IllegalStateException("Broken/cyclic hopper route: " + farm.id());
                    }
                    block = block.getRelative(((Directional) block.getBlockData()).getFacing());
                }
            }
            for (Farm other : farms) {
                if (other != farm && other.world().equals(farm.world())
                        && (other.x() >> 4) == (farm.x() >> 4) && (other.z() >> 4) == (farm.z() >> 4)) {
                    throw new IllegalStateException("Shared mechanism chunk");
                }
            }
            if (farm.entities()) {
                long hens = farm.world().getNearbyEntities(new Location(farm.world(), farm.x() + 8, 67, farm.z() + 8),
                        8, 4, 8).stream().filter(Chicken.class::isInstance).count();
                if (hens != 8) throw new IllegalStateException("Expected 8 hens, got " + hens);
            } else {
                for (int x = 2; x <= 14; x += 2) for (int z = 2; z <= 14; z += 2) {
                    if (farm.block(x, 66, z).getType() != Material.CACTUS) {
                        throw new IllegalStateException("Missing cactus: " + farm.id());
                    }
                }
            }
        }
    }

    private Map<String, Integer> inventory(Farm farm) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemStack item : farm.output().getInventory().getContents()) {
            if (item != null) counts.merge(item.getType().name(), item.getAmount(), Integer::sum);
        }
        return counts;
    }

    private void observe(CommandSender sender, List<Farm> selected, int ticks) {
        int number = ++observations;
        var record = new YamlConfiguration();
        record.set("requested-ticks", ticks);
        record.set("start-tick", Bukkit.getCurrentTick());
        record.set("server", Bukkit.getVersion());
        record.set("online-players-start", Bukkit.getOnlinePlayers().size());
        for (Farm farm : selected) record.set("farms." + farm.id() + ".before", inventory(farm));
        reply(sender, "FIXTURES_OBSERVING id=" + number + " ticks=" + ticks);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            record.set("end-tick", Bukkit.getCurrentTick());
            record.set("online-players-end", Bukkit.getOnlinePlayers().size());
            for (Farm farm : selected) {
                var after = inventory(farm);
                record.set("farms." + farm.id() + ".after", after);
                reply(sender, "FIXTURES_OUTPUT " + farm.id() + " " + after);
            }
            try {
                File destination = new File(getDataFolder(), "observations/" + System.currentTimeMillis() + "-" + number + ".yml");
                record.save(destination);
                reply(sender, "FIXTURES_OBSERVED id=" + number + " file=" + destination.getName());
            } catch (IOException error) {
                reply(sender, "FIXTURES_ERROR Could not save observation: " + error.getMessage());
            }
        }, ticks);
    }

    private void saveManifest() throws IOException {
        var saved = new YamlConfiguration();
        saved.set("fixture-version", 1);
        saved.set("seed", SEED);
        saved.set("ids", farms.stream().map(Farm::id).toList());
        saved.set("mechanism-relative-min", List.of(0, 63, 0));
        saved.set("mechanism-relative-max", List.of(15, 70, 15));
        saved.set("output-relative", List.of(16, 64, 7));
        for (Farm farm : farms) {
            String path = "farms." + farm.id();
            saved.set(path + ".world", farm.world().getName());
            saved.set(path + ".world-uuid", farm.world().getUID().toString());
            saved.set(path + ".folder", farm.world().getWorldFolder().getPath());
            saved.set(path + ".x", farm.x());
            saved.set(path + ".z", farm.z());
            saved.set(path + ".entities", farm.entities());
            saved.set(path + ".output-material", farm.entities() ? "EGG" : "CACTUS");
            if (!farm.id().startsWith("perf")) saved.set(path + ".internal-marker-barrel", List.of(0, 65, 0));
        }
        saved.save(manifest);
    }

    private void reply(CommandSender sender, String message) {
        if (sender != Bukkit.getConsoleSender()) sender.sendMessage(message);
        getLogger().info(message);
    }
}
