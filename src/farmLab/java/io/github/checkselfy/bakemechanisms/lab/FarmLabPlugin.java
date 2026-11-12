package io.github.checkselfy.bakemechanisms.lab;

import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.TileState;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BlockVector;
import org.bukkit.util.BlockTransformer;

/** Local test tooling only. Builds both imported farms before production snapshot recovery. */
public final class FarmLabPlugin extends JavaPlugin implements Listener {
    public record Farm(String id, String template, String comparison_of, int[] source_min, int[] source_max, int[] destination,
                       int[] size, int[] output, String product, List<String> adaptations) {
        public int[] maximum() { return new int[]{destination[0] + size[0] - 1,
                destination[1] + size[1] - 1, destination[2] + size[2] - 1}; }
    }
    private record Manifest(int format, int layout_version, String world, List<Farm> farms) {}
    private Manifest manifest;
    private World world;
    private MobFarmPlots mobs;
    private MobPerformanceWorld mobPerformance;

    public List<Farm> farms() { return manifest.farms(); }
    public World world() { return world; }

    @Override public void onEnable() {
        try {
            try (var input = new InputStreamReader(Objects.requireNonNull(getResource("fixtures/farms.json")), StandardCharsets.UTF_8)) {
                manifest = new Gson().fromJson(input, Manifest.class);
            }
            if (manifest.format() != 1) throw new IllegalStateException("Unknown lab manifest format");
            Path marker = getDataFolder().toPath().resolve("created.txt");
            var level = getServer().getWorlds().getFirst().getWorldFolder().toPath();
            var dimension = level.resolve("dimensions/minecraft/" + manifest.world());
            if (!Files.exists(marker) && Files.exists(dimension)) {
                throw new IllegalStateException("Unrecognized existing lab world; use a fresh server directory");
            }
            world = new WorldCreator(manifest.world()).seed(20261111L).generateStructures(false)
                    .generator(new ChunkGenerator() {
                        @Override public void generateNoise(WorldInfo info, Random random, int x, int z, ChunkData data) {
                            for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++) data.setBlock(dx, 63, dz, Material.STONE);
                        }
                    }).createWorld();
            Objects.requireNonNull(world);
            // Lets resetFarmLab find the Bake Mechanisms registrations that belong to this world.
            Files.createDirectories(getDataFolder().toPath());
            Files.writeString(getDataFolder().toPath().resolve("world-uid.txt"), world.getUID() + "\n");
            world.setGameRule(GameRules.ADVANCE_TIME, false);
            world.setGameRule(GameRules.ADVANCE_WEATHER, false);
            world.setGameRule(GameRules.SPAWN_MOBS, false);
            world.setGameRule(GameRules.RANDOM_TICK_SPEED, 3);
            world.setTime(6000);
            world.setSpawnLocation(48, 64, 24);
            if (!Files.exists(marker)) {
                for (var farm : farms()) if (farm.comparison_of() == null) build(farm, null);
                Files.createDirectories(marker.getParent());
                Files.writeString(marker, "Extracted PPL9 farm lab, format 1\n");
            }
            Path layout = getDataFolder().toPath().resolve("layout-v2.txt");
            if (!Files.exists(layout)) {
                // Preserve existing registrations/world edits: repair originals and add only new copies.
                for (var farm : farms()) if (farm.comparison_of() == null) containWater(farm);
                int removed = scanOutsideWater(true);
                for (var control : farms()) if (control.comparison_of() != null) {
                    ensureEmpty(control);
                    var original = find(control.comparison_of());
                    build(control, original);
                    verifyCopy(original, control);
                }
                removed += scanOutsideWater(true);
                Files.writeString(layout, "layout=2\nremoved-exterior-water=" + removed + "\n");
                getLogger().info("FARM_LAB_LAYOUT_UPDATED version=2 exterior-water-removed=" + removed);
            }
            mobs = new MobFarmPlots(this, world);
            mobs.initialize();
            mobPerformance = new MobPerformanceWorld(this);
            mobPerformance.initialize();
            // Every lab world ever generated here, so resetFarmLab also drops registrations of earlier lab worlds.
            var labWorlds = java.util.List.of(world.getUID(), mobPerformanceWorld().getUID());
            LabWorldHistory.record(getServer().getPluginsFolder().toPath().resolve(LabWorldHistory.FILE), labWorlds,
                    getServer().getWorlds().stream().map(World::getUID).filter(id -> !labWorlds.contains(id)).toList());
            Objects.requireNonNull(getCommand("farmlab")).setExecutor(this);
            getServer().getPluginManager().registerEvents(this, this);
            getLogger().info("FARM_LAB_READY farms=" + farms().size() + " world=" + world.getName());
        } catch (Exception error) {
            throw new IllegalStateException("Cannot prepare isolated farm lab", error);
        }
    }

    private void build(Farm farm, Farm original) throws Exception {
        org.bukkit.structure.Structure structure;
        if (original == null) {
            try (var input = Objects.requireNonNull(getResource("fixtures/structures/" + farm.template() + ".nbt"))) {
                structure = getServer().getStructureManager().loadStructure(input);
            }
        } else {
            structure = getServer().getStructureManager().createStructure();
            var size = original.size();
            structure.fill(location(original.destination()), new BlockVector(size[0], size[1], size[2]), false);
        }
            if (structure.getSize().getBlockX() != farm.size()[0] || structure.getSize().getBlockY() != farm.size()[1]
                    || structure.getSize().getBlockZ() != farm.size()[2]) throw new IllegalStateException("Structure size mismatch");
            BlockTransformer deferGrowth = (region, x, y, z, state, context) -> {
                if (!(state.getBlockData() instanceof Ageable)) return state;
                var empty = state.copy(); empty.setType(Material.AIR); return empty;
            };
            structure.place(location(farm.destination()), false, StructureRotation.NONE, Mirror.NONE, 0, 1.0f,
                    new Random(0), List.of(deferGrowth), List.of());
            for (var saved : structure.getPalettes().getFirst().getBlocks()) {
                if (saved instanceof TileState) continue;
                var p = farm.destination();
                var block = world.getBlockAt(p[0] + saved.getX(), p[1] + saved.getY(), p[2] + saved.getZ());
                if (!block.getBlockData().equals(saved.getBlockData())) block.setBlockData(saved.getBlockData(), false);
            }
        var p = farm.destination();
        if (farm.template().equals("cactus")) {
            // The original double trapped chest straddles the new mechanism boundary.
            hopper(p[0] + 2, p[1] + 1, p[2], BlockFace.NORTH);
        } else if (farm.template().equals("cane")) {
            hopper(p[0], p[1], p[2] + 8, BlockFace.WEST);
            for (int z = 9; z <= 14; z++) hopper(p[0], p[1], p[2] + z, BlockFace.NORTH);
        }
        var out = farm.output();
        world.getBlockAt(out[0], out[1], out[2]).setType(Material.BARREL, false);
        if (original != null) {
            var before = output(original).getContents();
            for (int i = 0; i < before.length; i++) if (before[i] != null) before[i] = before[i].clone();
            output(farm).setContents(before);
        }
        var low = farm.destination();
        var high = farm.maximum();
        world.getBlockAt(low[0], low[1], low[2]).setType(Material.GOLD_BLOCK, false);
        world.getBlockAt(high[0], high[1], high[2]).setType(Material.LIME_CONCRETE, false);
        containWater(farm);
        getLogger().info("FARM_LAB_IMPORTED " + farm.id() + " source=" + coordinates(farm.source_min())
                + ".." + coordinates(farm.source_max()) + " mechanism=" + coordinates(low) + ".." + coordinates(high)
                + " output=" + coordinates(out));
    }

    private void hopper(int x, int y, int z, BlockFace facing) {
        var block = world.getBlockAt(x, y, z);
        if (block.getType() != Material.HOPPER) {
            if (block.getState() instanceof org.bukkit.block.Container container) container.getInventory().clear();
            block.setType(Material.HOPPER, false);
        }
        var data = (Directional) block.getBlockData();
        data.setFacing(facing);
        block.setBlockData(data, false);
    }

    private Farm find(String id) { return farms().stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow(); }

    private void containWater(Farm farm) {
        var p = farm.destination();
        if (farm.template().equals("cactus")) {
            for (int z = 0; z <= 2; z++) {
                var edge = world.getBlockAt(p[0], p[1] + 1, p[2] + z);
                if (edge.getType() == Material.WATER) edge.setType(Material.AIR, false);
            }
        } else {
            for (int z : new int[]{8, 14}) {
                var cap = world.getBlockAt(p[0] - 1, p[1] + 1, p[2] + z);
                if (cap.getType().isAir() || cap.getType() == Material.WATER) cap.setType(Material.GLASS, false);
            }
        }
    }

    private boolean inside(Farm farm, int x, int y, int z) {
        var p = farm.destination(); var max = farm.maximum();
        return x >= p[0] && x <= max[0] && y >= p[1] && y <= max[1] && z >= p[2] && z <= max[2];
    }

    private int scanOutsideWater(boolean remove) {
        int count = 0;
        for (int x = -12; x <= 114; x++) for (int y = 64; y <= 103; y++) for (int z = -12; z <= 28; z++) {
            var block = world.getBlockAt(x, y, z);
            if (block.getType() != Material.WATER) continue;
            boolean within = false;
            for (var farm : farms()) if (inside(farm, x, y, z)) { within = true; break; }
            if (!within) {
                count++;
                if (remove) block.setType(Material.AIR, false);
            }
        }
        return count;
    }

    public int outsideWaterCount() { return scanOutsideWater(false); }

    private void ensureEmpty(Farm farm) {
        var low = farm.destination(); var high = farm.maximum();
        for (int x = low[0]; x <= high[0]; x++) for (int y = low[1]; y <= high[1]; y++) for (int z = low[2]; z <= high[2]; z++) {
            var type = world.getBlockAt(x, y, z).getType();
            if (!type.isAir() && type != Material.WATER) throw new IllegalStateException("Comparison plot occupied at " + x + "," + y + "," + z);
        }
    }

    private void verifyCopy(Farm original, Farm copy) {
        var a = original.destination(); var b = copy.destination(); var size = original.size();
        for (int x = 0; x < size[0]; x++) for (int y = 0; y < size[1]; y++) for (int z = 0; z < size[2]; z++) {
            var first = world.getBlockAt(a[0] + x, a[1] + y, a[2] + z);
            var second = world.getBlockAt(b[0] + x, b[1] + y, b[2] + z);
            if (!first.getBlockData().equals(second.getBlockData())) throw new IllegalStateException("Comparison block mismatch " + copy.id() + " at " + x + "," + y + "," + z);
            if (first.getState() instanceof org.bukkit.block.Container one && second.getState() instanceof org.bukkit.block.Container two
                    && !java.util.Arrays.equals(one.getInventory().getContents(), two.getInventory().getContents())) {
                throw new IllegalStateException("Comparison inventory mismatch " + copy.id());
            }
        }
        if (!java.util.Arrays.equals(output(original).getContents(), output(copy).getContents())) throw new IllegalStateException("Comparison output mismatch");
        getLogger().info("FARM_LAB_IDENTICAL_COPY " + original.id() + " -> " + copy.id() + " blocks/inventories/output equal before ticking");
    }

    private org.bukkit.inventory.Inventory output(Farm farm) {
        var p = farm.output();
        return ((org.bukkit.block.Container) world.getBlockAt(p[0], p[1], p[2]).getState()).getInventory();
    }

    private Map<String, Integer> counts(Farm farm) {
        var counts = new LinkedHashMap<String, Integer>();
        for (var item : output(farm).getContents()) if (item != null) counts.merge(item.getType().name(), item.getAmount(), Integer::sum);
        return counts;
    }

    private void compare(CommandSender sender, String id, int ticks) {
        if (ticks < 20 || ticks > 72000) throw new IllegalArgumentException("Use 20..72000 server ticks");
        var original = find(id);
        if (original.comparison_of() != null) original = find(original.comparison_of());
        final var first = original;
        final var second = find(first.id() + "-control");
        var beforeA = counts(first); var beforeB = counts(second);
        int start = getServer().getCurrentTick();
        int speed = world.getGameRuleValue(GameRules.RANDOM_TICK_SPEED);
        sender.sendMessage("Comparison started for " + ticks + " ticks at randomTickSpeed=" + speed
                + ". Stay near both farms; do not add/remove output items or change growth speed. Natural yields can differ.");
        getServer().getScheduler().runTaskLater(this, () -> {
            try {
                var afterA = counts(first); var afterB = counts(second);
                var record = new YamlConfiguration();
                record.set("start-tick", start); record.set("end-tick", getServer().getCurrentTick());
                record.set("requested-ticks", ticks); record.set("random-tick-speed-start", speed);
                record.set("random-tick-speed-end", world.getGameRuleValue(GameRules.RANDOM_TICK_SPEED));
                record.set(first.id() + ".before", beforeA); record.set(first.id() + ".after", afterA);
                record.set(second.id() + ".before", beforeB); record.set(second.id() + ".after", afterB);
                var path = getDataFolder().toPath().resolve("comparisons/" + System.currentTimeMillis() + "-" + first.id() + ".yml");
                Files.createDirectories(path.getParent()); record.save(path.toFile());
                Material product = Material.valueOf(first.product()); String type = product.name();
                sender.sendMessage(first.id() + ": " + beforeA + " -> " + afterA + " (" + type + " delta="
                        + (afterA.getOrDefault(type, 0) - beforeA.getOrDefault(type, 0)) + ")");
                sender.sendMessage(second.id() + ": " + beforeB + " -> " + afterB + " (" + type + " delta="
                        + (afterB.getOrDefault(type, 0) - beforeB.getOrDefault(type, 0)) + ")");
                sender.sendMessage("Comparison saved: " + path.getFileName());
            } catch (Exception error) { sender.sendMessage("Comparison failed: " + error.getMessage()); }
        }, ticks);
    }

    /** Records the inputs and result of a fixed-tick mob benchmark in a durable, reviewable file. */
    private void benchmark(CommandSender sender, String label, int ticks) throws Exception {
        if (!label.matches("[a-zA-Z0-9_-]{1,48}")) throw new IllegalArgumentException("Use a short letters/numbers/dashes label");
        if (ticks < 20 || ticks > 72000) throw new IllegalArgumentException("Use 20..72000 server ticks");
        var farms = mobPerformance.benchmarkFarms();
        var before = new LinkedHashMap<String, Map<String, Integer>>();
        for (var farm : farms) before.put(farm.id(), printableCounts(worldBlock(mobPerformanceWorld(), farm.output())));
        int start = getServer().getCurrentTick();
        var rates = benchmarkRates(mobPerformanceWorld().getUID());
        sender.sendMessage("Benchmark " + label + " started for " + ticks + " game ticks at the shared safe AFK position. Spark artifacts already present under plugins/spark are indexed in the result.");
        getServer().getScheduler().runTaskLater(this, () -> {
            try {
                var record = new YamlConfiguration();
                record.set("label", label); record.set("world", mobPerformanceWorld().getUID().toString());
                record.set("start-game-tick", start); record.set("end-game-tick", getServer().getCurrentTick());
                record.set("requested-game-ticks", ticks); record.set("elapsed-game-ticks", getServer().getCurrentTick() - start);
                record.set("safe-afk-profile-position", List.of(mobPerformanceWorld().getSpawnLocation().getBlockX(), 89, mobPerformanceWorld().getSpawnLocation().getBlockZ()));
                record.set("initial-inventory", before);
                var after = new LinkedHashMap<String, Map<String, Integer>>();
                for (var farm : farms) after.put(farm.id(), printableCounts(worldBlock(mobPerformanceWorld(), farm.output())));
                record.set("final-inventory", after); record.set("profile-rates-per-20-ticks", rates);
                record.set("spark-artifacts", sparkArtifacts());
                var path = getDataFolder().toPath().resolve("benchmarks/" + System.currentTimeMillis() + "-" + label + ".yml");
                Files.createDirectories(path.getParent()); record.save(path.toFile());
                sender.sendMessage("Benchmark recorded: " + path.getFileName());
            } catch (Exception error) { sender.sendMessage("Benchmark recording failed: " + error.getMessage()); }
        }, ticks);
    }

    private World mobPerformanceWorld() { return getServer().getWorld("farm_lab_mob_performance"); }
    private org.bukkit.block.Block worldBlock(World target, int[] position) { return Objects.requireNonNull(target).getBlockAt(position[0], position[1], position[2]); }
    private Map<String, Integer> printableCounts(org.bukkit.block.Block output) {
        var counts = new LinkedHashMap<String, Integer>();
        ResolvedOutputInventory.count(output).forEach((material, count) -> counts.put(material.getKey().toString(), count));
        return counts;
    }
    private Map<String, Object> benchmarkRates(UUID worldId) {
        var result = new LinkedHashMap<String, Object>();
        var storage = new io.github.checkselfy.bakemechanisms.storage.FarmStorage(
                getServer().getPluginManager().getPlugin("BakeMechanisms").getDataFolder().toPath().resolve("farms"));
        for (var farm : storage.loadAll()) if (farm.farm().mechanism().min().worldId().equals(worldId)) {
            if (farm.farm().profile() == null) { result.put(farm.farm().id().toString(), Map.of()); continue; }
            var outputs = new java.util.ArrayList<Map<String, Double>>();
            for (var output : farm.farm().profile().outputs()) {
                var formatted = new LinkedHashMap<String, Double>();
                output.forEach((material, rate) -> formatted.put(material.getKey().toString(), rate));
                outputs.add(formatted);
            }
            result.put(farm.farm().id().toString(), outputs);
        }
        return result;
    }
    private List<String> sparkArtifacts() throws java.io.IOException {
        var spark = getServer().getPluginsFolder().toPath().resolve("spark");
        if (!Files.isDirectory(spark)) return List.of();
        try (var paths = Files.walk(spark, 2)) {
            return paths.filter(Files::isRegularFile).map(path -> spark.relativize(path).toString()).sorted().toList();
        }
    }

    public Location location(int[] p) { return new Location(world, p[0], p[1], p[2]); }

    /** The lab is this server's only playable world: a player arriving anywhere else is sent to its centre. */
    @EventHandler public void onJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        if (player.getWorld().equals(world)) return;
        getServer().getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline() || player.getWorld().equals(world)) return;
            player.teleport(new Location(world, 48.5, 64, 24.5));
            player.sendMessage("Local farm lab: /farmlab setup, then /farmlab list. No OP required.");
        }, 5L);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            if (args.length == 1 && args[0].equals("list")) {
                for (var farm : farms()) sender.sendMessage(farm.id() + ": GOLD " + coordinates(farm.destination())
                        + " -> LIME " + coordinates(farm.maximum()) + "; output barrel " + coordinates(farm.output()));
                mobs.list(sender);
                mobPerformance.list(sender);
            } else if (args.length == 1 && args[0].equals("check-mobs")) {
                mobs.check(sender);
            } else if (args.length == 3 && args[0].equals("compare")) {
                compare(sender, args[1], Integer.parseInt(args[2]));
            } else if (args.length == 3 && args[0].equals("benchmark")) {
                benchmark(sender, args[1], Integer.parseInt(args[2]));
            } else if (sender instanceof Player player) {
                if (args.length == 1 && (args[0].equals("fast") || args[0].equals("normal"))) {
                    int speed = args[0].equals("fast") ? 30 : 3;
                    world.setGameRule(GameRules.RANDOM_TICK_SPEED, speed);
                    player.sendMessage("Lab randomTickSpeed=" + speed + ". Profile again after changing growth speed; record this setting with your result.");
                } else if (args.length == 1 && args[0].equals("stop")) {
                    player.sendMessage("Stopping the local lab cleanly. Restart with python scripts/farm-lab.py run.");
                    getServer().shutdown();
                } else if (args.length == 1 && args[0].equals("setup")) {
                    player.setGameMode(GameMode.CREATIVE);
                    player.teleport(new Location(world, 48.5, 65, 24.5));
                    player.sendMessage("Preparation: creative flight enabled; you remain non-OP. /bake wand; /farmlab corner <id> 1|2|output; /farmlab visit <id>.");
                } else if (args.length == 1 && (args[0].equals("survival") || args[0].equals("creative"))) {
                    player.setGameMode(args[0].equals("survival") ? GameMode.SURVIVAL : GameMode.CREATIVE);
                } else if (args.length >= 2 && args[0].equals("note")) {
                    var note = Instant.now() + " " + player.getName() + ": " + String.join(" ", List.of(args).subList(1, args.length)) + "\n";
                    Files.writeString(getDataFolder().toPath().resolve("manual-notes.txt"), note,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    player.sendMessage("Observation recorded in plugins/BakeFarmLab/manual-notes.txt");
                } else if (args.length >= 2) {
                    if (mobs.contains(args[1])) {
                        mobs.navigate(player, args);
                        return true;
                    }
                    if (mobPerformance.contains(args[1])) {
                        mobPerformance.navigate(player, args);
                        return true;
                    }
                    var farm = find(args[1]);
                    if (args.length == 2 && args[0].equals("visit")) {
                        player.teleport(new Location(world, farm.destination()[0] - 3.5, 64, farm.size()[2] / 2.0));
                    } else if (args.length == 3 && args[0].equals("corner")) {
                        var p = args[2].equals("1") ? farm.destination() : args[2].equals("2") ? farm.maximum()
                                : args[2].equals("output") ? farm.output() : null;
                        if (p == null) throw new IllegalArgumentException("Use 1, 2 or output");
                        if (args[2].equals("2")) {
                            player.setGameMode(GameMode.CREATIVE);
                            player.setFlying(true);
                        }
                        player.teleport(new Location(world, p[0] - 2.5, p[1] + 1, p[2] + 0.5));
                        player.sendMessage("Target block: " + coordinates(p) + ". Select it with the Bake wand.");
                    } else player.sendMessage(command.getUsage());
                } else player.sendMessage(command.getUsage());
            } else sender.sendMessage(command.getUsage());
        } catch (Exception error) {
            sender.sendMessage("Farm lab: " + error.getMessage());
        }
        return true;
    }

    private static String coordinates(int[] p) { return p[0] + "," + p[1] + "," + p[2]; }
}
