package io.github.checkselfy.bakemechanisms.lab;

import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.TileState;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wolf;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.loot.Lootable;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

/** Builds a scalable, real-PPL9 skeleton-spawner benchmark world; never mass-registers its farms. */
final class MobPerformanceWorld {
    private record Manifest(int format, String world, String template, int default_farms, int maximum_farms, int spacing) { }
    private record Farm(String id, int x, int z) {
        int[] min() { return new int[]{x, 64, z}; }
        int[] max() { return new int[]{x + 12, 88, z + 12}; }
        int[] output() { return new int[]{x - 1, 71, z + 6}; }
    }

    private final JavaPlugin plugin;
    private final Manifest manifest;
    private final List<Farm> farms = new ArrayList<>();
    private World world;

    MobPerformanceWorld(JavaPlugin plugin) throws Exception {
        this.plugin = plugin;
        try (var input = new InputStreamReader(Objects.requireNonNull(
                plugin.getResource("mob-performance-fixtures/farms.json")), StandardCharsets.UTF_8)) {
            manifest = new Gson().fromJson(input, Manifest.class);
        }
        if (manifest.format() != 1 || !manifest.template().equals("skeleton") || manifest.default_farms() < 1
                || manifest.default_farms() > manifest.maximum_farms() || manifest.spacing() < 16) {
            throw new IllegalStateException("Unknown mob-performance fixture manifest");
        }
    }

    void initialize() throws Exception {
        Path marker = plugin.getDataFolder().toPath().resolve("mob-performance-v1.txt");
        var level = plugin.getServer().getWorlds().getFirst().getWorldFolder().toPath();
        var dimension = level.resolve("dimensions/minecraft/" + manifest.world());
        if (!Files.exists(marker) && Files.exists(dimension)) {
            throw new IllegalStateException("Unrecognized existing mob-performance world; use a fresh server directory");
        }
        world = new WorldCreator(manifest.world()).seed(20261112L).generateStructures(false)
                .generator(new ChunkGenerator() {
                    @Override public void generateNoise(WorldInfo info, Random random, int chunkX, int chunkZ, ChunkData data) {
                        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) data.setBlock(x, 63, z, Material.STONE);
                    }
                }).createWorld();
        Objects.requireNonNull(world);
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setGameRule(GameRules.ADVANCE_WEATHER, false);
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.RANDOM_TICK_SPEED, 3);
        world.setTime(6000);
        world.setDifficulty(Difficulty.HARD);

        int columns = (int) Math.ceil(Math.sqrt(manifest.default_farms()));
        for (int i = 0; i < manifest.default_farms(); i++) {
            farms.add(new Farm("mobperf%02d".formatted(i + 1), (i % columns) * manifest.spacing(),
                    (i / columns) * manifest.spacing()));
        }
        world.setSpawnLocation((columns - 1) * manifest.spacing() / 2, 65,
                ((manifest.default_farms() + columns - 1) / columns - 1) * manifest.spacing() / 2);
        if (!Files.exists(marker)) {
            for (var farm : farms) build(farm);
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "world=" + world.getUID() + "\nfarms=" + farms.size() + "\n");
        }
        plugin.getLogger().info("FARM_LAB_MOB_PERFORMANCE_READY farms=" + farms.size() + " world=" + world.getName());
    }

    boolean contains(String id) { return farms.stream().anyMatch(farm -> farm.id().equals(id)); }

    void list(CommandSender sender) {
        for (var farm : farms) sender.sendMessage(farm.id() + ": GOLD " + coordinates(farm.min()) + " -> LIME "
                + coordinates(farm.max()) + "; double output chest " + coordinates(farm.output()));
    }

    void navigate(Player player, String[] args) {
        var farm = farms.stream().filter(candidate -> candidate.id().equals(args[1])).findFirst().orElseThrow();
        if (args.length == 2 && args[0].equals("visit")) {
            if (!player.teleport(new Location(world, farm.x() - 3.5, 74, farm.z() + 6.5))) {
                throw new IllegalStateException("Teleport refused by farm protection");
            }
            for (var entity : world.getNearbyEntities(box(farm))) if (entity instanceof Wolf wolf) {
                wolf.setOwner(player); wolf.setSitting(false);
            }
            player.sendMessage("AFK position for " + farm.id() + ". Stay here to activate its spawner; use the same active plots in both benchmark phases.");
        } else if (args.length == 3 && args[0].equals("corner")) {
            int[] target = switch (args[2]) {
                case "1" -> farm.min(); case "2" -> farm.max(); case "output" -> farm.output();
                default -> throw new IllegalArgumentException("Use 1, 2 or output");
            };
            if (args[2].equals("2")) { player.setGameMode(org.bukkit.GameMode.CREATIVE); player.setFlying(true); }
            if (!player.teleport(new Location(world, target[0] - 2.5, target[1] + 1, target[2] + .5))) {
                throw new IllegalStateException("Teleport refused by farm protection");
            }
            player.sendMessage("Target block: " + coordinates(target) + ". Select it with the Bake wand, then return with /farmlab visit " + farm.id() + ".");
        } else throw new IllegalArgumentException("Use /farmlab visit <id> or /farmlab corner <id> <1|2|output>");
    }

    private void build(Farm farm) throws Exception {
        try (var input = Objects.requireNonNull(plugin.getResource("mob-fixtures/structures/skeleton.nbt"))) {
            var structure = plugin.getServer().getStructureManager().loadStructure(input);
            var size = structure.getSize();
            if (size.getBlockX() != 13 || size.getBlockY() != 25 || size.getBlockZ() != 13) {
                throw new IllegalStateException("Skeleton performance structure dimensions changed");
            }
            structure.place(new Location(world, farm.x(), 64, farm.z()), false, StructureRotation.NONE, Mirror.NONE, 0, 1.0f, new Random(0));
            for (var saved : structure.getPalettes().getFirst().getBlocks()) if (!(saved instanceof TileState)) {
                at(farm, saved.getX(), saved.getY(), saved.getZ()).setBlockData(saved.getBlockData(), false);
            }
        }
        each(farm, block -> {
            var state = block.getState();
            if (state instanceof Lootable lootable) { lootable.setLootTable(null); state.update(true, false); }
            if (block.getState() instanceof Container container) container.getInventory().clear();
            if (block.getType() == Material.ENDER_CHEST || block.getType() == Material.COMPARATOR) block.setType(Material.AIR, false);
        });
        for (int x = 0; x <= 6; x++) hopper(farm, x, 7, 6, BlockFace.WEST);
        at(farm, 5, 9, 6).setType(Material.GLASS, false);
        chest(farm, -1, 7, 6, "west", "left"); chest(farm, -1, 7, 5, "west", "right");
        at(farm, -3, 9, 6).setType(Material.GLASS, false);
        var owner = plugin.getServer().getOfflinePlayer(UUID.nameUUIDFromBytes(("BakeFarmLab:" + farm.id()).getBytes(StandardCharsets.UTF_8)));
        for (int i = 0; i < 6; i++) {
            var wolf = (Wolf) world.spawnEntity(location(farm, 6.5, 9, 6.5), EntityType.WOLF);
            wolf.setOwner(owner); wolf.setSitting(false); wolf.setAdult(); wolf.setInvulnerable(true);
            wolf.setPersistent(true); wolf.setRemoveWhenFarAway(false);
        }
        var spawner = (CreatureSpawner) at(farm, 6, 20, 6).getState();
        spawner.setMinSpawnDelay(80); spawner.setMaxSpawnDelay(120); spawner.setDelay(1);
        spawner.setSpawnCount(4); spawner.setMaxNearbyEntities(24); spawner.update(true, false);
        at(farm, 0, 0, 0).setType(Material.GOLD_BLOCK, false);
        at(farm, 12, 24, 12).setType(Material.LIME_CONCRETE, false);
        if (((Container) at(farm, -1, 7, 6).getState()).getInventory().getSize() != 54) {
            throw new IllegalStateException("Mob performance output not paired: " + farm.id());
        }
    }

    private BoundingBox box(Farm farm) { return new BoundingBox(farm.x(), 64, farm.z(), farm.x() + 13, 89, farm.z() + 13); }
    private void each(Farm farm, Consumer<Block> action) { for (int x = 0; x <= 12; x++) for (int y = 0; y <= 24; y++) for (int z = 0; z <= 12; z++) action.accept(at(farm, x, y, z)); }
    private Block at(Farm farm, int x, int y, int z) { return world.getBlockAt(farm.x() + x, 64 + y, farm.z() + z); }
    private Location location(Farm farm, double x, double y, double z) { return new Location(world, farm.x() + x, 64 + y, farm.z() + z); }
    private void hopper(Farm farm, int x, int y, int z, BlockFace face) { at(farm, x, y, z).setType(Material.HOPPER, false); var data = (org.bukkit.block.data.type.Hopper) at(farm, x, y, z).getBlockData(); data.setFacing(face); at(farm, x, y, z).setBlockData(data, false); }
    private void chest(Farm farm, int x, int y, int z, String facing, String type) { at(farm, x, y, z).setBlockData(plugin.getServer().createBlockData("minecraft:chest[facing=" + facing + ",type=" + type + "]"), false); }
    private static String coordinates(int[] p) { return p[0] + "," + p[1] + "," + p[2]; }
}
