package io.github.checkselfy.bakemechanisms.correctness;

import com.google.gson.Gson;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.State;
import io.github.checkselfy.bakemechanisms.storage.FarmStorage;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
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
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wolf;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.loot.Lootable;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

/** Imported PPL9 spawner farms. Native spawning, AI, combat, drops and collection; no injected mob loot. */
final class MobFarmReview implements Listener {
    private record Spec(String id, int[] source_min, int[] source_max, int[] destination, int[] size, int[] spawner, int[] output) {}
    private record Manifest(int format, List<Spec> farms) {}
    private static final class Farm {
        final Spec spec;
        UUID id;
        int spawned;
        int died;
        Map<String, String> blocks = new HashMap<>();
        Map<String, ItemStack[]> inventories = new HashMap<>();
        Set<UUID> entities;
        int spawnerDelay;
        final Map<Material, Integer> harvested = new EnumMap<>(Material.class);
        Map<Material, Integer> initial;
        Farm(Spec spec) { this.spec = spec; }
    }
    private final JavaPlugin plugin;
    private final FarmStorage storage;
    private final List<Farm> farms = new ArrayList<>();
    private final Set<UUID> nativeSpawns = new HashSet<>();
    private World world;
    private Player a;
    private Player b;
    private int phase;
    private long ticks;
    private long emulationTicks;
    private ItemStack armorSample;
    private boolean originalArmorPreserved;
    private int plainArmor;
    private int creatorXp;
    private int otherXp;
    private int profileAttempts;

    MobFarmReview(JavaPlugin plugin) {
        this.plugin = plugin;
        storage = new FarmStorage(plugin.getServer().getPluginManager().getPlugin("BakeMechanisms")
                .getDataFolder().toPath().resolve("farms"));
    }

    void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            try { tick(); }
            catch (Throwable error) {
                plugin.getLogger().log(Level.SEVERE, "REVIEW_CORRECTNESS_FAILED mobs phase=" + phase, error);
                plugin.getServer().getScheduler().cancelTasks(plugin);
                plugin.getServer().shutdown();
            }
        }, 1L, 1L);
    }

    private void tick() throws Exception {
        if (phase == 0) {
            a = plugin.getServer().getPlayer("ReviewCreator"); b = plugin.getServer().getPlayer("ReviewOther");
            if (a == null || b == null) return;
            require(!a.isOp() && !b.isOp(), "mob review clients must be non-OP");
            try (var input = new InputStreamReader(Objects.requireNonNull(plugin.getResource("mob-fixtures/farms.json")), StandardCharsets.UTF_8)) {
                var manifest = new Gson().fromJson(input, Manifest.class);
                require(manifest.format() == 1 && manifest.farms().size() == 2, "unknown mob fixture manifest");
                manifest.farms().forEach(spec -> farms.add(new Farm(spec)));
            }
            world = Objects.requireNonNull(new WorldCreator("mob_farm_review").generateStructures(false)
                    .generator(new ChunkGenerator() {}).createWorld());
            world.setDifficulty(Difficulty.HARD);
            world.setGameRule(GameRules.SPAWN_MOBS, false);
            world.setGameRule(GameRules.ADVANCE_TIME, false);
            world.setFullTime(738000);
            a.setGameMode(GameMode.SURVIVAL); b.setGameMode(GameMode.SURVIVAL);
            a.setInvulnerable(true); b.setInvulnerable(true);
            for (var farm : farms) build(farm);
            world.getBlockAt(-3, 73, 6).setType(Material.GLASS, false);
            world.getBlockAt(80, 69, 6).setType(Material.GLASS, false);
            require(a.teleport(new Location(world, -2.8, 74, 6.5)), "cannot position skeleton observer");
            require(b.teleport(new Location(world, 80.8, 70, 6.5)), "cannot position zombie observer");
            for (int i = 0; i < 6; i++) {
                var wolf = (Wolf) world.spawnEntity(new Location(world, 6.5, 73, 6.5), EntityType.WOLF);
                wolf.setOwner(a); wolf.setSitting(false); wolf.setAdult(); wolf.setInvulnerable(true);
                wolf.setRemoveWhenFarAway(false);
            }
            for (var farm : farms) for (var chunk : chunks(farm)) chunk.removePluginChunkTicket(plugin);
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), "tick rate 100");
            pass("MOB_SETUP", "two imported PPL9 spawner mechanisms; native loot/equipment unchanged; six invulnerable tame wolves; accelerated spawner delays only");
            next(1);
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), "tick sprint 24000t");
            return;
        }
        require(a.isOnline() && b.isOnline(), "mob review client disconnected");
        ticks++;
        if (phase == 1) {
            if (ticks % 1000 == 0) status("MOB_WARMUP");
            armorSample = findNaturalArmor();
            if (armorSample == null && output(farms.getFirst()).firstEmpty() == -1) {
                pass("MOB_WARMUP_DRAIN", "withdrawing observed skeleton loot to keep room for a native enchanted armor sample: " + counts(farms.getFirst()));
                output(farms.getFirst()).clear();
            }
            if (ticks < 2000 || armorSample == null || counts(farms.get(1)).getOrDefault(Material.ROTTEN_FLESH, 0) == 0) {
                require(ticks < 24000, "no native damaged enchanted armor / zombie output; see spawn/death counters");
                return;
            }
            require(farms.stream().allMatch(f -> f.spawned > 0 && f.died > 0), "mob products lack native spawning/death evidence");
            Files.createDirectories(plugin.getDataFolder().toPath());
            Files.write(plugin.getDataFolder().toPath().resolve("natural-armor.bin"), armorSample.serializeAsBytes());
            pass("C07_NATIVE_ARMOR", "naturally spawned skeleton dropped " + describe(armorSample) + "; collected through original hopper chain; no equipment/drop-chance changes");
            status("MOB_NATURAL_OUTPUT");
            startProfiles();
        } else if (phase == 2) {
            if (ticks % 2000 == 0) status("MOB_PROFILING");
            if (ticks < io.github.checkselfy.bakemechanisms.profiling.ProfilingSettings.DURATION_TICKS) return;
            for (var farm : farms) {
                var saved = stored(farm).farm();
                require(saved.state() == State.READY, "native mob farm did not complete profiling: " + farm.spec.id() + " state=" + saved.state());
                pass("MOB_PROFILE", farm.spec.id() + " usefulTicks=" + saved.profile().usefulTicks() + " rates=" + saved.profile().ratesPer20Ticks());
            }
            var profile = stored(farms.getFirst()).farm().profile();
            var profiledArmor = findNaturalArmor();
            if (profiledArmor == null || !profile.ratesPer20Ticks().containsKey(profiledArmor.getType())) {
                require(profileAttempts < 8, "eight native profiling windows did not collect damaged enchanted armor");
                pass("MOB_PROFILE_REPEAT", "retrying independent native window; no artificial drops are inserted");
                startProfiles();
                return;
            }
            armorSample = profiledArmor;
            Files.write(plugin.getDataFolder().toPath().resolve("profiled-natural-armor.bin"), armorSample.serializeAsBytes());
            pass("C07_PROFILED_ARMOR", "measured native input=" + describe(armorSample));
            double armorRate = profile.ratesPer20Ticks().get(armorSample.getType());
            emulationTicks = 20 * ((long) Math.ceil(1.0 / armorRate) + 2);
            require(emulationTicks <= io.github.checkselfy.bakemechanisms.profiling.ProfilingSettings.DURATION_TICKS + 60,
                    "natural armor profile needs an unexpectedly long verification");
            next(4);
        } else if (phase == 4) {
            // Death animation entities are no longer valid/persistent snapshot actors. Let native
            // death finish rather than deleting living mobs or weakening production safety checks.
            for (var entity : world.getEntitiesByClass(ExperienceOrb.class)) entity.remove();
            var unfinished = farms.stream().flatMap(f -> world.getNearbyEntities(box(f)).stream())
                    .filter(e -> !(e instanceof Player) && (!e.isValid() || !e.isPersistent())).toList();
            if (!unfinished.isEmpty()) {
                require(ticks < 600, "native entities never became snapshot eligible: " + unfinished.stream()
                        .map(e -> e.getType() + " valid=" + e.isValid() + " persistent=" + e.isPersistent()).toList());
                return;
            }
            creatorXp = a.getTotalExperience(); otherXp = b.getTotalExperience();
            for (var farm : farms) {
                output(farm).clear();
                if (farm == farms.getFirst()) output(farm).setItem(0, armorSample.clone());
                farm.initial = counts(farm);
                snapshotBaseline(farm);
                command("stasis --id " + farm.id);
                require(stored(farm).farm().state() == State.STASIS, "mob farm failed to enter stasis");
                each(farm, block -> require(block.getType() == Material.BEDROCK, "mob mechanism not entirely filler"));
            }
            require(world.getEntities().stream().allMatch(e -> e instanceof Player), "original entities survived beside emulation");
            pass("MOB_STASIS", "spawners, wolves and live mobs snapshotted; complete filler; only emulation remains; accounting duration=" + emulationTicks
                    + "; native snapshot-eligibility wait=" + ticks);
            next(3);
        } else if (phase == 3) {
            if (ticks % 20 == 0) for (var farm : farms) harvest(farm);
            if (ticks < emulationTicks) return;
            for (var farm : farms) verifyAccounting(farm);
            require(plainArmor > 0 && originalArmorPreserved, "natural armor was not preserved separately from normalized emulation output");
            require(world.getEntitiesByClass(ExperienceOrb.class).isEmpty() && a.getTotalExperience() == creatorXp
                    && b.getTotalExperience() == otherXp, "mob emulation awarded experience");
            pass("C07_MOB_NORMALIZATION", "natural original metadata unchanged; plain full-durability armor generated; no XP; all whole items/fractions verified");
            for (var farm : farms) {
                command("unstasis --id " + farm.id);
                require(stored(farm).farm().state() == State.NEEDS_PROFILING, "restored mob farm retained profile");
                verifyRestored(farm);
                command("unregister --id " + farm.id); command("unregister --id " + farm.id + " confirm");
            }
            require(storage.loadAll().isEmpty(), "mob review leaked registrations");
            pass("MOB_FARMS_PASSED", "two native mob producers; natural armor, normalization, exact accounting, XP isolation and complete restore verified");
            plugin.getServer().shutdown(); next(99);
        }
    }

    private void build(Farm farm) throws Exception {
        var spec = farm.spec;
        var p = spec.destination();
        var origin = new Location(world, p[0], p[1], p[2]);
        try (var input = Objects.requireNonNull(plugin.getResource("mob-fixtures/structures/" + spec.id() + ".nbt"))) {
            var structure = plugin.getServer().getStructureManager().loadStructure(input);
            require(structure.getSize().getBlockX() == spec.size()[0] && structure.getSize().getBlockY() == spec.size()[1]
                    && structure.getSize().getBlockZ() == spec.size()[2], "mob structure dimensions differ from manifest");
            structure.place(origin, false, StructureRotation.NONE, Mirror.NONE, 0, 1.0f, new Random(0));
            for (var block : structure.getPalettes().getFirst().getBlocks()) if (!(block instanceof TileState)) {
                world.getBlockAt(p[0] + block.getX(), p[1] + block.getY(), p[2] + block.getZ()).setBlockData(block.getBlockData(), false);
            }
        }
        each(farm, block -> {
            var state = block.getState();
            if (state instanceof Lootable lootable) { lootable.setLootTable(null); state.update(true, false); }
            if (block.getState() instanceof Container container) container.getInventory().clear();
            if (block.getType() == Material.ENDER_CHEST) block.setType(Material.AIR, false);
            // The original sorter is bypassed by the external collector. Its unaudited
            // comparator tiles are not part of the adapted renewable producer.
            if (block.getType() == Material.COMPARATOR) block.setType(Material.AIR, false);
        });
        if (spec.id().equals("skeleton")) {
            for (int x = 0; x <= 6; x++) hopper(x, 71, 6, BlockFace.WEST);
            world.getBlockAt(5, 73, 6).setType(Material.GLASS, false);
            chest(-1, 71, 6, "west", "left"); chest(-1, 71, 5, "west", "right");
        } else {
            for (int x = 74; x <= 77; x++) hopper(x, 67, 2, BlockFace.EAST);
            world.getBlockAt(74, 68, 2).setBlockData(plugin.getServer().createBlockData("minecraft:oak_fence_gate[open=true,powered=false,facing=north]"), false);
            world.getBlockAt(73, 69, 2).setType(Material.GLASS, false);
            world.getBlockAt(74, 69, 3).setType(Material.GLASS, false);
            world.getBlockAt(74, 69, 2).setType(Material.LAVA, false);
            chest(78, 67, 2, "east", "left"); chest(78, 67, 3, "east", "right");
        }
        require(output(farm).getSize() == 54, "mob output halves did not form a double chest");
        output(farm).clear();
        var spawner = spawner(farm);
        require(spawner.getSpawnedType() == (spec.id().equals("skeleton") ? EntityType.SKELETON : EntityType.ZOMBIE), "imported spawner type changed");
        spawner.setMinSpawnDelay(80); spawner.setMaxSpawnDelay(120); spawner.setDelay(1);
        spawner.setSpawnCount(4); spawner.setMaxNearbyEntities(24);
        require(spawner.update(true, false), "cannot configure native test spawner timing");
        for (var chunk : chunks(farm)) { chunk.setInhabitedTime(3_600_000); chunk.addPluginChunkTicket(plugin); }
        var max = box(farm);
        var region = new io.github.checkselfy.bakemechanisms.marking.Cuboid(
                new io.github.checkselfy.bakemechanisms.marking.BlockPosition(world.getUID(), p[0], p[1], p[2]),
                new io.github.checkselfy.bakemechanisms.marking.BlockPosition(world.getUID(), (int) max.getMaxX() - 1, (int) max.getMaxY() - 1, (int) max.getMaxZ() - 1));
        var resolver = new io.github.checkselfy.bakemechanisms.marking.PaperOutputResolver();
        var out = spec.output();
        new io.github.checkselfy.bakemechanisms.stasis.PaperStasisWorld(plugin, resolver).capture(
                new io.github.checkselfy.bakemechanisms.marking.FarmRegistry.MarkedFarm(UUID.randomUUID(), a.getUniqueId(), region,
                        resolver.resolve(world.getBlockAt(out[0], out[1], out[2])), State.READY,
                        new io.github.checkselfy.bakemechanisms.profiling.ProductionProfile(Map.of(Material.BONE, 1.0), 3600)));
        pass("MOB_IMPORTED", spec.id() + " source=" + Arrays.toString(spec.source_min()) + ".." + Arrays.toString(spec.source_max()));
    }

    private void startProfiles() {
        for (var farm : farms) {
            if (farm.id != null) { command("unregister --id " + farm.id); command("unregister --id " + farm.id + " confirm"); }
            clearStock(farm);
            farm.id = register(farm);
            command("profile --id " + farm.id);
            require(stored(farm).farm().state() == State.PROFILING, "mob profiling did not start");
        }
        profileAttempts++;
        next(2);
    }

    @EventHandler public void spawned(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.SPAWNER) return;
        for (var farm : farms) if (box(farm).contains(event.getLocation().toVector()) && event.getLocation().getWorld() == world) {
            farm.spawned++; nativeSpawns.add(event.getEntity().getUniqueId());
        }
    }
    @EventHandler public void died(EntityDeathEvent event) {
        if (!nativeSpawns.contains(event.getEntity().getUniqueId())) return;
        for (var farm : farms) if (box(farm).contains(event.getEntity().getLocation().toVector())) {
            farm.died++;
            for (var item : event.getDrops()) if (armor(item.getType()) && item.hasItemMeta()) {
                pass("NATIVE_ARMOR_DROP", "spawnReason=SPAWNER type=" + event.getEntity().getType() + " killer="
                        + (event.getEntity().getKiller() == null ? "none" : event.getEntity().getKiller().getName()) + " " + describe(item));
            }
        }
    }

    private UUID register(Farm farm) {
        a.getInventory().clear(); command("wand"); a.getInventory().setHeldItemSlot(0);
        var low = farm.spec.destination(); var size = farm.spec.size();
        click(Action.RIGHT_CLICK_BLOCK, low[0] + size[0] - 1, low[1] + size[1] - 1, low[2] + size[2] - 1, false);
        click(Action.LEFT_CLICK_BLOCK, low[0], low[1], low[2], false);
        var out = farm.spec.output(); click(Action.RIGHT_CLICK_BLOCK, out[0], out[1], out[2], true);
        command("register Mob" + farm.spec.id());
        return storage.loadAll().stream().filter(f -> ("Mob" + farm.spec.id()).equals(f.farm().name())).findFirst().orElseThrow().farm().id();
    }
    private void click(Action action, int x, int y, int z, boolean sneak) {
        a.setSneaking(sneak);
        plugin.getServer().getPluginManager().callEvent(new PlayerInteractEvent(a, action, a.getInventory().getItemInMainHand(),
                world.getBlockAt(x, y, z), BlockFace.UP, EquipmentSlot.HAND));
        a.setSneaking(false);
    }
    private void clearStock(Farm farm) {
        each(farm, block -> { if (block.getState() instanceof Container container) container.getInventory().clear(); });
        output(farm).clear();
        // Existing mob equipment is finite starting stock: #25 deliberately rejects its material
        // during profiling. Start from the renewable spawner, keeping the persistent wolf producers.
        for (var entity : world.getNearbyEntities(box(farm))) if (entity instanceof Item
                || entity.getType() == EntityType.SKELETON || entity.getType() == EntityType.ZOMBIE) entity.remove();
    }
    private void snapshotBaseline(Farm farm) {
        each(farm, block -> {
            farm.blocks.put(key(block), block.getBlockData().getAsString());
            if (block.getState() instanceof Container container) {
                var contents = container.getInventory().getContents();
                farm.inventories.put(key(block), Arrays.stream(contents).map(i -> i == null ? null : i.clone()).toArray(ItemStack[]::new));
            }
        });
        farm.entities = entityIds(farm); farm.spawnerDelay = spawner(farm).getDelay();
    }
    private void verifyRestored(Farm farm) {
        each(farm, block -> {
            require(farm.blocks.get(key(block)).equals(block.getBlockData().getAsString()), "mob restore changed block " + key(block));
            if (farm.inventories.containsKey(key(block))) require(Arrays.equals(farm.inventories.get(key(block)),
                    ((Container) block.getState()).getInventory().getContents()), "mob restore changed inventory " + key(block));
        });
        require(entityIds(farm).equals(farm.entities), "mob restore lost/duplicated entity UUIDs");
        require(spawner(farm).getDelay() == farm.spawnerDelay, "mob restore changed native spawner delay");
        pass("MOB_RESTORE", farm.spec.id() + " every block/inventory, native spawner delay and " + farm.entities.size() + " entity UUIDs restored exactly");
    }
    private void harvest(Farm farm) {
        for (var item : output(farm).getContents()) if (item != null) {
            farm.harvested.merge(item.getType(), item.getAmount(), Integer::sum);
            if (armor(item.getType())) {
                if (Arrays.equals(item.serializeAsBytes(), armorSample.serializeAsBytes())) originalArmorPreserved = true;
                else { require(!item.hasItemMeta(), "emulation retained armor properties"); plainArmor += item.getAmount(); }
            }
        }
        output(farm).clear();
    }
    private void verifyAccounting(Farm farm) {
        var saved = stored(farm);
        var progress = saved.emulation().orElseThrow().outputs().getFirst();
        for (var rate : saved.farm().profile().ratesPer20Ticks().entrySet()) {
            var total = BigDecimal.valueOf(rate.getValue()).multiply(BigDecimal.valueOf(emulationTicks)).divide(BigDecimal.valueOf(20));
            int expected = total.setScale(0, RoundingMode.FLOOR).intValueExact();
            int actual = farm.harvested.getOrDefault(rate.getKey(), 0) - farm.initial.getOrDefault(rate.getKey(), 0)
                    + progress.buffer().getOrDefault(rate.getKey(), 0);
            require(actual == expected && total.remainder(BigDecimal.ONE).compareTo(progress.fractions().get(rate.getKey())) == 0,
                    "mob emulation whole/fraction mismatch: " + farm.spec.id() + " " + rate.getKey());
            pass("MOB_ACCOUNTING", farm.spec.id() + " type=" + rate.getKey() + " ticks=" + emulationTicks + " whole=" + actual + " fraction=" + total.remainder(BigDecimal.ONE));
        }
    }
    private ItemStack findNaturalArmor() {
        for (var item : output(farms.getFirst()).getContents()) if (item != null && armor(item.getType())
                && item.getItemMeta() instanceof Damageable damage && damage.getDamage() > 0 && !item.getEnchantments().isEmpty()) return item.clone();
        return null;
    }
    private static boolean armor(Material type) {
        return type.name().endsWith("_HELMET") || type.name().endsWith("_CHESTPLATE") || type.name().endsWith("_LEGGINGS") || type.name().endsWith("_BOOTS");
    }
    private static String describe(ItemStack item) {
        return item.getType() + " damage=" + ((Damageable) item.getItemMeta()).getDamage() + " enchants=" + item.getEnchantments();
    }
    private Set<UUID> entityIds(Farm farm) {
        var ids = new HashSet<UUID>();
        for (var entity : world.getNearbyEntities(box(farm))) if (!(entity instanceof Player)) ids.add(entity.getUniqueId());
        return ids;
    }
    private BoundingBox box(Farm farm) {
        var p = farm.spec.destination(); var size = farm.spec.size();
        return new BoundingBox(p[0], p[1], p[2], p[0] + size[0], p[1] + size[1], p[2] + size[2]);
    }
    private List<org.bukkit.Chunk> chunks(Farm farm) {
        var result = new ArrayList<org.bukkit.Chunk>(); var p = farm.spec.destination(); var size = farm.spec.size();
        for (int x = p[0] >> 4; x <= (p[0] + size[0] - 1) >> 4; x++) for (int z = p[2] >> 4; z <= (p[2] + size[2] - 1) >> 4; z++) result.add(world.getChunkAt(x, z));
        return result;
    }
    private void each(Farm farm, Consumer<Block> action) {
        var p = farm.spec.destination(); var size = farm.spec.size();
        for (int x = 0; x < size[0]; x++) for (int y = 0; y < size[1]; y++) for (int z = 0; z < size[2]; z++) action.accept(world.getBlockAt(p[0] + x, p[1] + y, p[2] + z));
    }
    private void hopper(int x, int y, int z, BlockFace face) {
        world.getBlockAt(x, y, z).setType(Material.HOPPER, false);
        var data = (org.bukkit.block.data.type.Hopper) world.getBlockAt(x, y, z).getBlockData();
        data.setFacing(face); world.getBlockAt(x, y, z).setBlockData(data, false);
    }
    private void chest(int x, int y, int z, String facing, String type) {
        world.getBlockAt(x, y, z).setBlockData(plugin.getServer().createBlockData("minecraft:chest[facing=" + facing + ",type=" + type + "]"), false);
    }
    private CreatureSpawner spawner(Farm farm) { var p = farm.spec.spawner(); return (CreatureSpawner) world.getBlockAt(p[0], p[1], p[2]).getState(); }
    private org.bukkit.inventory.Inventory output(Farm farm) { var p = farm.spec.output(); return ((Container) world.getBlockAt(p[0], p[1], p[2]).getState()).getInventory(); }
    private Map<Material, Integer> counts(Farm farm) {
        var counts = new EnumMap<Material, Integer>(Material.class);
        for (var item : output(farm).getContents()) if (item != null) counts.merge(item.getType(), item.getAmount(), Integer::sum);
        return counts;
    }
    private FarmStorage.StoredFarm stored(Farm farm) { return storage.loadAll().stream().filter(f -> f.farm().id().equals(farm.id)).findFirst().orElseThrow(); }
    private void command(String command) { require(a.performCommand("bake " + command), "unknown bake command"); }
    private void status(String name) { for (var farm : farms) pass(name, farm.spec.id() + " native-spawned=" + farm.spawned + " native-deaths=" + farm.died + " output=" + counts(farm)); }
    private void next(int next) { phase = next; ticks = 0; }
    private static String key(Block block) { return block.getX() + "," + block.getY() + "," + block.getZ(); }
    private void pass(String name, String detail) { plugin.getLogger().info("REVIEW_PASS " + name + " tick=" + plugin.getServer().getCurrentTick() + " " + detail); }
    private static void require(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
