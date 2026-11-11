package io.github.checkselfy.bakemechanisms.lab;

import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
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
import org.bukkit.loot.Lootable;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

/** One-time additive migration of manual mob plots; existing plots are never rebuilt on restart. */
final class MobFarmPlots {
    private static final int OFFSET_X = 160;
    private record Spec(String id, int[] destination, int[] size, int[] spawner, int[] output) {}
    private record Manifest(int format, List<Spec> farms) {}
    private final JavaPlugin plugin;
    private final World world;
    private final List<Spec> farms;

    MobFarmPlots(JavaPlugin plugin, World world) throws Exception {
        this.plugin = plugin; this.world = world;
        try (var input = new InputStreamReader(Objects.requireNonNull(plugin.getResource("mob-fixtures/farms.json")), StandardCharsets.UTF_8)) {
            var manifest = new Gson().fromJson(input, Manifest.class);
            if (manifest.format() != 1 || manifest.farms().size() != 2) throw new IllegalStateException("Unknown mob plot manifest");
            farms = manifest.farms();
        }
    }

    void initialize() throws Exception {
        var pending = new ArrayList<Spec>();
        for (var farm : farms) if (!Files.exists(marker(farm))) {
            // Validate all new plots before editing any of them. Do not overwrite user builds.
            each(farm, block -> {
                if (!block.getType().isAir()) throw new IllegalStateException("New mob plot occupied at " + block.getLocation());
            });
            for (int z : chestZ(farm)) if (!at(farm.output()[0], farm.output()[1], z).getType().isAir()) {
                throw new IllegalStateException("New mob output plot is occupied: " + farm.id());
            }
            var platform = farm.id().equals("skeleton") ? at(-3, 73, 6) : at(80, 69, 6);
            if (!platform.getType().isAir() || !world.getNearbyEntities(box(farm)).isEmpty()) {
                throw new IllegalStateException("New mob plot/platform contains an existing build or entity: " + farm.id());
            }
            pending.add(farm);
        }
        if (!pending.isEmpty()) {
            world.setDifficulty(Difficulty.HARD);
            world.setFullTime(Math.max(world.getFullTime(), 720000) / 24000 * 24000 + 6000);
        }
        for (var farm : pending) {
            build(farm);
            Files.createDirectories(marker(farm).getParent());
            Files.writeString(marker(farm), "world=" + world.getUID() + "\nplot=" + farm.id() + "\n");
        }
        plugin.getLogger().info("FARM_LAB_MOBS_READY farms=" + farms.size() + " ids=skeleton,zombie");
    }

    private java.nio.file.Path marker(Spec farm) { return plugin.getDataFolder().toPath().resolve("mob-" + farm.id() + "-v1.txt"); }
    boolean contains(String id) { return farms.stream().anyMatch(f -> f.id().equals(id)); }
    private Spec find(String id) { return farms.stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow(); }

    void list(CommandSender sender) {
        for (var f : farms) sender.sendMessage(f.id() + ": GOLD " + coordinates(f.destination()) + " -> LIME " + coordinates(maximum(f))
                + "; double output chest " + coordinates(f.output()) + "; /farmlab visit " + f.id());
    }

    void check(CommandSender sender) {
        for (var farm : farms) {
            if (!(at(farm.spawner()).getState() instanceof CreatureSpawner spawner)
                    || spawner.getSpawnedType() != (farm.id().equals("skeleton") ? EntityType.SKELETON : EntityType.ZOMBIE)) {
                throw new IllegalStateException("Mob plot spawner unavailable (restore stasis first): " + farm.id());
            }
            if (!(((Container) at(farm.output()).getState()).getInventory().getSize() == 54)) throw new IllegalStateException("Mob double chest missing");
        }
        sender.sendMessage("FARM_LAB_MOBS_CHECK_OK farms=2");
    }

    void navigate(Player player, String[] args) {
        var farm = find(args[1]);
        if (args.length == 2 && args[0].equals("visit")) {
            var target = farm.id().equals("skeleton") ? location(-2.8, 74, 6.5) : location(80.8, 70, 6.5);
            if (!player.teleport(target)) throw new IllegalStateException("Teleport refused by farm protection");
            if (farm.id().equals("skeleton")) {
                for (var entity : world.getNearbyEntities(box(farm))) if (entity instanceof Wolf wolf) {
                    wolf.setOwner(player); wolf.setSitting(false);
                }
            }
            player.sendMessage("AFK position for " + farm.id() + ". Stay here to activate the spawner; empty the output before profiling.");
        } else if (args.length == 3 && args[0].equals("corner")) {
            int[] p = switch (args[2]) { case "1" -> farm.destination(); case "2" -> maximum(farm); case "output" -> farm.output(); default -> throw new IllegalArgumentException("Use 1, 2 or output"); };
            if (args[2].equals("2")) { player.setGameMode(org.bukkit.GameMode.CREATIVE); player.setFlying(true); }
            if (!player.teleport(location(p[0] - 2.5, p[1] + 1, p[2] + .5))) throw new IllegalStateException("Teleport refused by farm protection");
            player.sendMessage("Target block: " + coordinates(p) + ". Select it with the Bake wand. Return with /farmlab visit " + farm.id() + ".");
        } else throw new IllegalArgumentException("Use /farmlab visit <id> or /farmlab corner <id> 1|2|output");
    }

    private void build(Spec farm) throws Exception {
        var p = farm.destination();
        try (var input = Objects.requireNonNull(plugin.getResource("mob-fixtures/structures/" + farm.id() + ".nbt"))) {
            var structure = plugin.getServer().getStructureManager().loadStructure(input);
            var size = structure.getSize();
            if (size.getBlockX() != farm.size()[0] || size.getBlockY() != farm.size()[1] || size.getBlockZ() != farm.size()[2]) throw new IllegalStateException("Mob structure dimensions changed");
            structure.place(location(p[0], p[1], p[2]), false, StructureRotation.NONE, Mirror.NONE, 0, 1.0f, new Random(0));
            for (var saved : structure.getPalettes().getFirst().getBlocks()) if (!(saved instanceof TileState)) {
                at(p[0] + saved.getX(), p[1] + saved.getY(), p[2] + saved.getZ()).setBlockData(saved.getBlockData(), false);
            }
        }
        each(farm, block -> {
            var state = block.getState();
            if (state instanceof Lootable lootable) { lootable.setLootTable(null); state.update(true, false); }
            if (block.getState() instanceof Container container) container.getInventory().clear();
            if (block.getType() == Material.ENDER_CHEST || block.getType() == Material.COMPARATOR) block.setType(Material.AIR, false);
        });
        if (farm.id().equals("skeleton")) {
            for (int x = 0; x <= 6; x++) hopper(x, 71, 6, BlockFace.WEST);
            at(5, 73, 6).setType(Material.GLASS, false);
            chest(-1, 71, 6, "west", "left"); chest(-1, 71, 5, "west", "right");
            at(-3, 73, 6).setType(Material.GLASS, false);
            var owner = plugin.getServer().getOfflinePlayer(UUID.nameUUIDFromBytes("BakeFarmLab:skeleton".getBytes(StandardCharsets.UTF_8)));
            for (int i = 0; i < 6; i++) {
                var wolf = (Wolf) world.spawnEntity(location(6.5, 73, 6.5), EntityType.WOLF);
                wolf.setOwner(owner); wolf.setSitting(false); wolf.setAdult(); wolf.setInvulnerable(true);
                wolf.setPersistent(true); wolf.setRemoveWhenFarAway(false);
            }
        } else {
            for (int x = 74; x <= 77; x++) hopper(x, 67, 2, BlockFace.EAST);
            at(74, 68, 2).setBlockData(plugin.getServer().createBlockData("minecraft:oak_fence_gate[open=true,powered=false,facing=north]"), false);
            at(73, 69, 2).setType(Material.GLASS, false); at(74, 69, 3).setType(Material.GLASS, false);
            at(74, 69, 2).setType(Material.LAVA, false);
            chest(78, 67, 2, "east", "left"); chest(78, 67, 3, "east", "right");
            at(80, 69, 6).setType(Material.GLASS, false);
        }
        var spawner = (CreatureSpawner) at(farm.spawner()).getState();
        spawner.setMinSpawnDelay(80); spawner.setMaxSpawnDelay(120); spawner.setDelay(1);
        spawner.setSpawnCount(4); spawner.setMaxNearbyEntities(24); spawner.update(true, false);
        for (int x = (p[0] + OFFSET_X) >> 4; x <= (maximum(farm)[0] + OFFSET_X) >> 4; x++) {
            for (int z = p[2] >> 4; z <= maximum(farm)[2] >> 4; z++) world.getChunkAt(x, z).setInhabitedTime(3_600_000);
        }
        at(p).setType(Material.GOLD_BLOCK, false); at(maximum(farm)).setType(Material.LIME_CONCRETE, false);
        if (((Container) at(farm.output()).getState()).getInventory().getSize() != 54) throw new IllegalStateException("Mob output not paired");
        plugin.getLogger().info("FARM_LAB_MOB_IMPORTED " + farm.id() + " min=" + coordinates(p) + " max=" + coordinates(maximum(farm)));
    }

    private int[] chestZ(Spec farm) { return farm.id().equals("skeleton") ? new int[]{6, 5} : new int[]{2, 3}; }
    private int[] maximum(Spec farm) { var p = farm.destination(); var s = farm.size(); return new int[]{p[0] + s[0] - 1, p[1] + s[1] - 1, p[2] + s[2] - 1}; }
    private BoundingBox box(Spec farm) { var p = farm.destination(); var m = maximum(farm); return new BoundingBox(p[0] + OFFSET_X, p[1], p[2], m[0] + OFFSET_X + 1, m[1] + 1, m[2] + 1); }
    private void each(Spec farm, Consumer<Block> action) { var p = farm.destination(); var m = maximum(farm); for (int x = p[0]; x <= m[0]; x++) for (int y = p[1]; y <= m[1]; y++) for (int z = p[2]; z <= m[2]; z++) action.accept(at(x, y, z)); }
    private Block at(int[] p) { return at(p[0], p[1], p[2]); }
    private Block at(int x, int y, int z) { return world.getBlockAt(x + OFFSET_X, y, z); }
    private Location location(double x, double y, double z) { return new Location(world, x + OFFSET_X, y, z); }
    private String coordinates(int[] p) { return (p[0] + OFFSET_X) + "," + p[1] + "," + p[2]; }
    private void hopper(int x, int y, int z, BlockFace face) { at(x, y, z).setType(Material.HOPPER, false); var data = (org.bukkit.block.data.type.Hopper) at(x, y, z).getBlockData(); data.setFacing(face); at(x, y, z).setBlockData(data, false); }
    private void chest(int x, int y, int z, String facing, String type) { at(x, y, z).setBlockData(plugin.getServer().createBlockData("minecraft:chest[facing=" + facing + ",type=" + type + "]"), false); }
}
