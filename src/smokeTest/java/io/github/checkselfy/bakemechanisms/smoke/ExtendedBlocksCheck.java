package io.github.checkselfy.bakemechanisms.smoke;

import io.github.checkselfy.bakemechanisms.marking.BlockPosition;
import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry;
import io.github.checkselfy.bakemechanisms.marking.PaperOutputResolver;
import io.github.checkselfy.bakemechanisms.profiling.ProductionProfile;
import io.github.checkselfy.bakemechanisms.stasis.FileSnapshotStore;
import io.github.checkselfy.bakemechanisms.stasis.PaperStasisWorld;
import io.github.checkselfy.bakemechanisms.stasis.StasisService;
import io.papermc.paper.block.TileStateInventoryHolder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Beacon;
import org.bukkit.block.Beehive;
import org.bukkit.block.Block;
import org.bukkit.block.Crafter;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.Furnace;
import org.bukkit.block.Lectern;
import org.bukkit.block.BrewingStand;
import org.bukkit.block.Skull;
import org.bukkit.block.TileState;
import org.bukkit.entity.Bee;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

/** Real native NBT checks, exclusively on the isolated smoke server. */
@SuppressWarnings("deprecation")
final class ExtendedBlocksCheck {
    private static final Set<Material> FIXED = Set.of(Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER,
            Material.CRAFTER, Material.SPAWNER, Material.BEACON, Material.BEEHIVE, Material.BEE_NEST,
            Material.LECTERN, Material.BREWING_STAND);

    static void run(JavaPlugin plugin) throws Exception {
        var world = plugin.getServer().getWorlds().getFirst();
        var resolver = new PaperOutputResolver();
        var adapter = new PaperStasisWorld(plugin, resolver);
        var registry = new FarmRegistry(FarmRegistry.DEFAULT_MAX_VOLUME);
        var store = new FileSnapshotStore(plugin.getDataFolder().toPath().resolve(UUID.randomUUID().toString()), "26.3");
        var service = new StasisService(registry, adapter, store);
        var bounds = new BoundingBox(191, 298, -1, 225, 306, 33);
        var materials = Arrays.stream(Material.values()).filter(material -> !material.name().startsWith("LEGACY_")
                && material.isBlock() && (FIXED.contains(material) || material.name().endsWith("SHULKER_BOX")
                || material.name().endsWith("_SKULL") || material.name().endsWith("_HEAD") && material != Material.PISTON_HEAD)).toList();
        check(!materials.isEmpty() && materials.size() <= 80, "extended fixture bounds need updating");
        for (int x = 192; x <= 223; x++) for (int y = 300; y <= 303; y++) for (int z = 0; z <= 31; z++) {
            var block = world.getBlockAt(x, y, z);
            clear(block);
            block.setType(y == 300 ? Material.STONE : Material.AIR, false);
        }
        var key = new NamespacedKey(plugin, "extended_check");
        var expected = new LinkedHashMap<BlockPosition, Map<String, Object>>();
        for (int i = 0; i < materials.size(); i++) {
            var material = materials.get(i);
            int x = 193 + i % 8 * 3;
            int z = 1 + i / 8 * 3;
            var data = plugin.getServer().createBlockData(material);
            if (data instanceof org.bukkit.block.data.Directional directional) {
                directional.setFacing(org.bukkit.block.BlockFace.EAST);
                if (material.name().contains("WALL")) world.getBlockAt(x - 1, 301, z).setType(Material.STONE, false);
            }
            var block = world.getBlockAt(x, 301, z);
            block.setBlockData(data, false);
            var state = (TileState) block.getState();
            state.getPersistentDataContainer().set(key, PersistentDataType.STRING, "retained-" + material);
            if (state instanceof org.bukkit.Nameable nameable) nameable.customName(Component.text("Snapshot " + material));
            if (state instanceof org.bukkit.block.Lockable lockable) lockable.setLock("smoke-lock");
            configure(plugin, world, block, state);
            check(state.update(true, false), "could not initialize " + material);
            expected.put(position(block), fingerprint((TileState) block.getState(), key));
        }
        // A void cell in a normal structure must not remain bedrock when the template skips it.
        var voidBlock = world.getBlockAt(222, 301, 30);
        voidBlock.setType(Material.STRUCTURE_VOID, false);
        clearEntities(world, bounds);
        var flying = (Bee) world.spawnEntity(new Location(world, 220.5, 301, 28.5), EntityType.BEE);
        flying.setPersistent(true);
        flying.setAI(false);
        flying.setHasNectar(true);
        flying.customName(Component.text("Flying bee"));
        UUID beeId = flying.getUniqueId();
        var region = new Cuboid(new BlockPosition(world.getUID(), 192, 300, 0), new BlockPosition(world.getUID(), 223, 303, 31));
        var output = world.getBlockAt(224, 300, 0);
        output.setType(Material.BARREL, false);
        UUID creator = UUID.randomUUID();
        var farm = registry.register(creator, region, resolver.resolve(output));
        var profile = new ProductionProfile(Map.of(Material.COBBLESTONE, 1.0), 3600);
        registry.markReady(farm.id(), profile);
        service.enter(farm.id(), creator);
        check(world.getNearbyEntities(bounds).isEmpty(), "replacement released items, XP or bees");
        check(voidBlock.getType() == Material.BEDROCK, "void cell was not replaced");
        service.restore(farm.id(), creator);
        assertRestored(world, expected, key);
        check(voidBlock.getType() == Material.STRUCTURE_VOID, "void cell not restored");
        check(world.getNearbyEntities(bounds).size() == 1, "restoration produced duplicate entities");
        check(plugin.getServer().getEntity(beeId) instanceof Bee bee && bee.hasNectar(), "flying bee data lost");
        registry.markReady(farm.id(), profile);
        service.enter(farm.id(), creator);
        var recovered = new StasisService(new FarmRegistry(1), adapter, store);
        recovered.recover();
        recovered.restore(farm.id(), creator);
        assertRestored(world, expected, key);
        check(voidBlock.getType() == Material.STRUCTURE_VOID, "void cell not recovered from disk");
        check(world.getNearbyEntities(bounds).size() == 1, "disk recovery produced duplicate entities");
        for (var point : expected.keySet()) {
            var state = world.getBlockAt(point.x(), point.y(), point.z()).getState();
            if (state instanceof Beehive hive) {
                var released = hive.releaseEntities();
                check(released.size() == 1 && released.getFirst().hasNectar(), "stored bee data lost");
                check(Component.text("Stored " + hive.getType()).equals(released.getFirst().customName()), "stored bee name lost");
            }
        }
        check(world.getNearbyEntities(bounds).size() == 3, "stored bees duplicated or lost");
        checkPureVoid(plugin, world, resolver, adapter);
        plugin.getLogger().info("EXTENDED_BLOCK_STASIS_SMOKE_PASSED: " + materials.size() + " materials");
    }

    private static void configure(JavaPlugin plugin, World world, Block block, TileState state) {
        if (state instanceof TileStateInventoryHolder holder) {
            var item = new ItemStack(Material.DIAMOND_PICKAXE);
            var meta = item.getItemMeta();
            meta.displayName(Component.text("Stored " + block.getType()));
            meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 3, true);
            item.setItemMeta(meta);
            holder.getSnapshotInventory().setItem(0, item);
        }
        if (state instanceof Furnace furnace) {
            furnace.getSnapshotInventory().setItem(0, new ItemStack(Material.RAW_IRON, 6));
            furnace.getSnapshotInventory().setItem(1, new ItemStack(Material.COAL, 3));
            furnace.getSnapshotInventory().setItem(2, new ItemStack(Material.IRON_INGOT, 2));
            furnace.setBurnTime((short) 120);
            furnace.setCookTime((short) 17);
            furnace.setCookTimeTotal(200);
            furnace.setCookSpeedMultiplier(1.25);
            var recipes = plugin.getServer().recipeIterator();
            while (recipes.hasNext()) {
                var recipe = recipes.next();
                if (recipe instanceof CookingRecipe<?> cooking && (
                        state.getType() == Material.FURNACE && recipe instanceof org.bukkit.inventory.FurnaceRecipe
                        || state.getType() == Material.BLAST_FURNACE && recipe instanceof org.bukkit.inventory.BlastingRecipe
                        || state.getType() == Material.SMOKER && recipe instanceof org.bukkit.inventory.SmokingRecipe)) {
                    furnace.setRecipeUsedCount(cooking, 3);
                    break;
                }
            }
        }
        if (state instanceof Crafter crafter) {
            crafter.setSlotDisabled(2, true);
            crafter.setSlotDisabled(7, true);
            crafter.setCraftingTicks(6);
            crafter.setTriggered(true);
        }
        if (state instanceof CreatureSpawner spawner) {
            spawner.setSpawnedType(EntityType.COW);
            spawner.setMinSpawnDelay(200);
            spawner.setMaxSpawnDelay(900);
            spawner.setDelay(321);
            spawner.setSpawnCount(7);
            spawner.setMaxNearbyEntities(9);
            spawner.setRequiredPlayerRange(12);
            spawner.setSpawnRange(5);
        }
        if (state instanceof Beacon beacon) {
            beacon.setPrimaryEffect(org.bukkit.potion.PotionEffectType.SPEED);
            beacon.setSecondaryEffect(org.bukkit.potion.PotionEffectType.REGENERATION);
            beacon.setEffectRange(48.5);
        }
        if (state instanceof Beehive hive) {
            hive.setMaxEntities(4);
            hive.setFlower(new Location(world, block.getX() + 1, 300, block.getZ()));
            var bee = (Bee) world.spawnEntity(new Location(world, block.getX() + 0.5, 301, block.getZ() + 0.5), EntityType.BEE);
            bee.setHasNectar(true);
            bee.customName(Component.text("Stored " + state.getType()));
            hive.addEntity(bee);
            bee.remove();
        }
        if (state instanceof Lectern lectern) {
            var book = new ItemStack(Material.WRITTEN_BOOK);
            var meta = (BookMeta) book.getItemMeta();
            meta.setTitle("Снимок книги");
            meta.setAuthor("BakeMechanisms");
            meta.pages(Component.text("Первая"), Component.text("Вторая"), Component.text("Третья"));
            book.setItemMeta(meta);
            lectern.getSnapshotInventory().setItem(0, book);
            var data = (org.bukkit.block.data.type.Lectern) lectern.getBlockData();
            data.setHasBook(true);
            lectern.setBlockData(data);
            lectern.setPage(2);
        }
        if (state instanceof BrewingStand brewing) {
            brewing.getSnapshotInventory().setItem(0, new ItemStack(Material.POTION));
            brewing.getSnapshotInventory().setItem(3, new ItemStack(Material.NETHER_WART, 2));
            brewing.setBrewingTime(123);
            brewing.setRecipeBrewTime(456);
            brewing.setFuelLevel(9);
        }
        if (state instanceof Skull skull) {
            skull.customName(Component.text("Head " + state.getType()));
            skull.setNoteBlockSound(NamespacedKey.minecraft("block.note_block.bell"));
            if (state.getType() == Material.PLAYER_HEAD || state.getType() == Material.PLAYER_WALL_HEAD) {
                var profile = plugin.getServer().createProfile(UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"), "SnapshotHead");
                var texture = Base64.getEncoder().encodeToString(("{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/"
                        + "0".repeat(64) + "\"}}}").getBytes(StandardCharsets.UTF_8));
                profile.setProperty(new com.destroystokyo.paper.profile.ProfileProperty("textures", texture));
                skull.setPlayerProfile(profile);
            }
        }
    }

    private static Map<String, Object> fingerprint(TileState state, NamespacedKey key) {
        var data = new LinkedHashMap<String, Object>();
        data.put("block", state.getBlockData().getAsString());
        data.put("pdc", state.getPersistentDataContainer().get(key, PersistentDataType.STRING));
        if (state instanceof org.bukkit.Nameable nameable) data.put("name", nameable.customName());
        if (state instanceof org.bukkit.block.Lockable lockable) data.put("lock", lockable.getLock());
        if (state instanceof TileStateInventoryHolder holder) {
            data.put("items", Arrays.stream(holder.getSnapshotInventory().getContents())
                    .map(item -> item == null || item.isEmpty() ? null : item.clone()).toList());
        }
        if (state instanceof Furnace furnace) {
            data.put("furnace", List.of(furnace.getBurnTime(), furnace.getCookTime(), furnace.getCookTimeTotal(), furnace.getCookSpeedMultiplier()));
            var recipes = new LinkedHashMap<String, Integer>();
            furnace.getRecipesUsed().forEach((recipe, count) -> recipes.put(recipe.getKey().toString(), count));
            data.put("recipes", recipes);
        }
        if (state instanceof Crafter crafter) {
            var disabled = new ArrayList<Integer>();
            for (int i = 0; i < 9; i++) if (crafter.isSlotDisabled(i)) disabled.add(i);
            data.put("crafter", List.of(disabled, crafter.getCraftingTicks(), crafter.isTriggered()));
        }
        if (state instanceof CreatureSpawner spawner) data.put("spawner", List.of(spawner.getSpawnedType(), spawner.getDelay(),
                spawner.getMinSpawnDelay(), spawner.getMaxSpawnDelay(), spawner.getSpawnCount(),
                spawner.getMaxNearbyEntities(), spawner.getRequiredPlayerRange(), spawner.getSpawnRange()));
        if (state instanceof Beacon beacon) {
            data.put("primary", beacon.getPrimaryEffect() == null ? null : beacon.getPrimaryEffect().getType());
            data.put("secondary", beacon.getSecondaryEffect() == null ? null : beacon.getSecondaryEffect().getType());
            data.put("range", beacon.getEffectRange());
        }
        if (state instanceof Beehive hive) data.put("hive", List.of(hive.getEntityCount(), hive.getMaxEntities(), hive.getFlower()));
        if (state instanceof Lectern lectern) data.put("page", lectern.getPage());
        if (state instanceof BrewingStand brewing) data.put("brewing", List.of(brewing.getBrewingTime(), brewing.getFuelLevel(), brewing.getRecipeBrewTime()));
        if (state instanceof Skull skull) {
            data.put("head-name", skull.customName());
            data.put("sound", skull.getNoteBlockSound());
            var profile = skull.getPlayerProfile();
            if (profile != null) data.put("profile", List.of(profile.getId(), profile.getName(), profile.getProperties()));
        }
        return data;
    }

    private static void assertRestored(World world, Map<BlockPosition, Map<String, Object>> expected, NamespacedKey key) {
        expected.forEach((position, before) -> {
            var state = world.getBlockAt(position.x(), position.y(), position.z()).getState();
            check(state instanceof TileState, "block entity missing at " + position);
            var after = fingerprint((TileState) state, key);
            check(before.equals(after), "metadata changed at " + position + ": before=" + before + ", after=" + after);
        });
    }

    private static void checkPureVoid(JavaPlugin plugin, World world, PaperOutputResolver resolver, PaperStasisWorld adapter) throws Exception {
        var block = world.getBlockAt(288, 300, 0);
        block.setType(Material.STRUCTURE_VOID, false);
        var output = world.getBlockAt(289, 300, 0);
        output.setType(Material.BARREL, false);
        var registry = new FarmRegistry(1);
        var store = new FileSnapshotStore(plugin.getDataFolder().toPath().resolve(UUID.randomUUID().toString()), "26.3");
        var service = new StasisService(registry, adapter, store);
        UUID creator = UUID.randomUUID();
        var farm = registry.register(creator, new Cuboid(position(block), position(block)), resolver.resolve(output));
        registry.markReady(farm.id(), new ProductionProfile(Map.of(Material.COBBLESTONE, 1.0), 3600));
        service.enter(farm.id(), creator);
        service.restore(farm.id(), creator);
        check(block.getType() == Material.STRUCTURE_VOID, "pure void snapshot did not restore");
    }

    private static void clear(Block block) {
        var state = block.getState(false);
        if (state instanceof org.bukkit.inventory.InventoryHolder holder) holder.getInventory().clear();
        if (state instanceof Furnace furnace) { furnace.setRecipesUsed(Map.of()); furnace.update(true, false); }
        if (state instanceof Beehive hive) { hive.clearEntities(); hive.update(true, false); }
    }

    private static void clearEntities(World world, BoundingBox bounds) {
        var entities = world.getNearbyEntities(bounds);
        for (var entity : entities) if (entity instanceof org.bukkit.inventory.InventoryHolder holder) holder.getInventory().clear();
        entities.forEach(org.bukkit.entity.Entity::remove);
    }

    private static BlockPosition position(Block block) { return PaperOutputResolver.position(block); }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
