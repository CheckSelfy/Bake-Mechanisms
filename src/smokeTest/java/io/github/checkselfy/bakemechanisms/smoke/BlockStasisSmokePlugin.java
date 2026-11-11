package io.github.checkselfy.bakemechanisms.smoke;

import io.github.checkselfy.bakemechanisms.marking.BlockPosition;
import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.State;
import io.github.checkselfy.bakemechanisms.marking.OutputContainer;
import io.github.checkselfy.bakemechanisms.marking.PaperOutputResolver;
import io.github.checkselfy.bakemechanisms.profiling.ProductionProfile;
import io.github.checkselfy.bakemechanisms.stasis.FileSnapshotStore;
import io.github.checkselfy.bakemechanisms.stasis.PaperStasisWorld;
import io.github.checkselfy.bakemechanisms.stasis.StasisService;
import io.github.checkselfy.bakemechanisms.stasis.StasisWorld;
import io.github.checkselfy.bakemechanisms.stasis.SnapshotStore;
import io.github.checkselfy.bakemechanisms.stasis.SavedSnapshot;
import java.io.IOException;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Test-only plugin, deployed exclusively to run/stasis-smoke by the stasisSmoke Gradle task. */
public final class BlockStasisSmokePlugin extends JavaPlugin {
    @Override public void onEnable() {
        getServer().getScheduler().runTask(this, () -> {
            try {
                checkSnapshots();
                getLogger().info("BLOCK_STASIS_SMOKE_PASSED");
                MovingPistonCheck.run(this, error -> {
                    if (error == null) {
                        try {
                            var wheat = getServer().getWorlds().getFirst().getBlockAt(4, 301, 3);
                            check(wheat.getType() == Material.WHEAT
                                    && ((org.bukkit.block.data.Ageable) wheat.getBlockData()).getAge() == 7,
                                    "restored mature wheat did not survive subsequent server ticks");
                            getLogger().info("FRAGILE_BLOCK_STASIS_SMOKE_PASSED: mature wheat retained after placement and subsequent ticks");
                        } catch (Throwable failure) { error = failure; }
                    }
                    if (error != null) getLogger().log(Level.SEVERE, "BLOCK_STASIS_SMOKE_FAILED", error);
                    getServer().shutdown();
                });
            } catch (Throwable error) {
                getLogger().log(Level.SEVERE, "BLOCK_STASIS_SMOKE_FAILED", error);
                getServer().shutdown();
            }
        });
    }

    private void checkSnapshots() throws Exception {
        World world = getServer().getWorlds().getFirst();
        var registry = new FarmRegistry(FarmRegistry.DEFAULT_MAX_VOLUME);
        var resolver = new PaperOutputResolver();
        var adapter = new PaperStasisWorld(this, resolver);
        var store = new FileSnapshotStore(getDataFolder().toPath().resolve(UUID.randomUUID().toString()), "26.3");
        var service = new StasisService(registry, adapter, store);
        UUID creator = UUID.randomUUID();
        var region = new Cuboid(point(world, 0, 300, 0), point(world, 5, 302, 5));
        clearTestEntities(world);
        var expectedBlocks = new LinkedHashMap<BlockPosition, String>();
        for (int x = 0; x <= 5; x++) for (int y = 300; y <= 302; y++) for (int z = 0; z <= 5; z++) {
            world.getBlockAt(x, y, z).setType(y == 300 ? Material.STONE : Material.AIR, false);
        }
        var expectedItems = new LinkedHashMap<BlockPosition, ItemStack>();
        Material[] containers = { Material.CHEST, Material.BARREL, Material.HOPPER, Material.DISPENSER, Material.DROPPER };
        for (int i = 0; i < containers.length; i++) {
            var position = point(world, i, 301, 1);
            var block = world.getBlockAt(i, 301, 1);
            block.setType(containers[i], false);
            var item = new ItemStack(Material.DIAMOND_PICKAXE);
            var meta = item.getItemMeta();
            meta.displayName(Component.text("Snapshot check " + i));
            meta.addEnchant(Enchantment.UNBREAKING, 3, true);
            meta.getPersistentDataContainer().set(new NamespacedKey(this, "check"), PersistentDataType.STRING, "retained");
            item.setItemMeta(meta);
            ((Container) block.getState(false)).getInventory().setItem(0, item);
            expectedItems.put(position, item.clone());
        }
        // Both halves of an internal double chest need independent slot data in the snapshot.
        world.getBlockAt(0, 301, 1).setBlockData(getServer().createBlockData("minecraft:chest[facing=east,type=left,waterlogged=false]"), false);
        world.getBlockAt(0, 301, 2).setBlockData(getServer().createBlockData("minecraft:chest[facing=east,type=right,waterlogged=false]"), false);
        var rightItem = new ItemStack(Material.GOLD_INGOT, 13);
        ((org.bukkit.block.Chest) world.getBlockAt(0, 301, 2).getState(false)).getBlockInventory().setItem(0, rightItem);
        expectedItems.put(point(world, 0, 301, 2), rightItem.clone());
        // Non-default directional data and a waterlogged block exercise more than material identity.
        world.getBlockAt(1, 301, 3).setBlockData(getServer().createBlockData("minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=true]"), false);
        // Imported real farm contains mature wheat: native placement must retain fragile plants too.
        world.getBlockAt(4, 300, 3).setBlockData(getServer().createBlockData("minecraft:farmland[moisture=7]"), false);
        world.getBlockAt(4, 301, 3).setBlockData(getServer().createBlockData("minecraft:wheat[age=7]"), false);
        for (int x = 0; x <= 5; x++) for (int y = 300; y <= 302; y++) for (int z = 0; z <= 5; z++) {
            expectedBlocks.put(point(world, x, y, z), world.getBlockAt(x, y, z).getBlockData().getAsString());
        }
        var outputBlock = world.getBlockAt(32, 300, 0);
        outputBlock.setType(Material.BARREL, false);
        var outputItem = new ItemStack(Material.DIAMOND, 27);
        ((Container) outputBlock.getState(false)).getInventory().setItem(0, outputItem);
        var farm = registry.register(creator, region, resolver.resolve(outputBlock));
        var profile = new ProductionProfile(Map.of(Material.COBBLESTONE, 1.0), 3600);
        registry.markReady(farm.id(), profile);
        // A previous failed run may have left entities in chunks loaded by fixture construction.
        clearTestEntities(world);
        // A real native capture followed by a deterministic disk failure must leave every block/item intact.
        var failingStore = new SnapshotStore() {
            public void save(SavedSnapshot snapshot) throws IOException { throw new IOException("review: injected save failure"); }
            public List<SavedSnapshot> loadActive() { return List.of(); }
            public void deactivate(UUID id) { throw new AssertionError("unexpected deactivate"); }
        };
        expectRefused(() -> new StasisService(registry, adapter, failingStore).enter(farm.id(), creator));
        assertRestored(world, expectedBlocks, expectedItems);
        check(registry.requireCreator(farm.id(), creator).state() == State.READY, "save failure changed state");
        check(store.loadActive().isEmpty(), "save failure activated snapshot");
        check(outputItem.equals(((Container) outputBlock.getState(false)).getInventory().getItem(0)), "save failure changed output");
        getLogger().info("SNAPSHOT_SAVE_FAILURE_SMOKE_PASSED");
        service.enter(farm.id(), creator);
        assertBedrock(world, expectedBlocks);
        check(registry.requireCreator(farm.id(), creator).state() == State.STASIS, "state after enter");
        check(outputItem.equals(((Container) outputBlock.getState(false)).getInventory().getItem(0)), "output changed");
        check(world.getNearbyEntities(new org.bukkit.util.BoundingBox(-1, 299, -1, 7, 304, 7)).isEmpty(), "replacement dropped items");
        service.restore(farm.id(), creator);
        assertRestored(world, expectedBlocks, expectedItems);
        check(registry.requireCreator(farm.id(), creator).profile() == null, "old profile retained");

        // Simulate a partial restore error after native placement; all blocks must return to filler.
        registry.markReady(farm.id(), profile);
        var failOnce = new StasisWorld() {
            boolean fail = true;
            public boolean hasPlayerInside(Cuboid box) { return adapter.hasPlayerInside(box); }
            public byte[] capture(FarmRegistry.MarkedFarm marked) { return adapter.capture(marked); }
            public void removeEntities(FarmRegistry.MarkedFarm marked, byte[] data) { adapter.removeEntities(marked, data); }
            public void fill(Cuboid box) { adapter.fill(box); }
            public void restore(FarmRegistry.MarkedFarm marked, byte[] data) {
                adapter.restore(marked, data);
                if (fail) { fail = false; throw new IllegalStateException("injected partial restore error"); }
            }
        };
        var retryService = new StasisService(registry, failOnce, store);
        retryService.enter(farm.id(), creator);
        try {
            retryService.restore(farm.id(), creator);
            throw new AssertionError("partial failure was not propagated");
        } catch (IllegalArgumentException expected) {
            assertBedrock(world, expectedBlocks);
            check(store.loadActive().size() == 1, "failed restore lost snapshot");
        }
        retryService.restore(farm.id(), creator);
        assertRestored(world, expectedBlocks, expectedItems);

        // Reload the actual files into a new registry and restore without using the original memory.
        registry.markReady(farm.id(), profile);
        service.enter(farm.id(), creator);
        var recoveredRegistry = new FarmRegistry(1);
        var recovered = new StasisService(recoveredRegistry, adapter, store);
        recovered.recover();
        assertBedrock(world, expectedBlocks);
        recovered.restore(farm.id(), creator);
        assertRestored(world, expectedBlocks, expectedItems);

        // Unsupported tile state and entity must fail before replacing a single block.
        registry.markNeedsProfiling(farm.id());
        world.getBlockAt(0, 301, 0).setType(Material.COMMAND_BLOCK, false);
        registry.markReady(farm.id(), profile);
        expectRefused(() -> service.enter(farm.id(), creator));
        check(world.getBlockAt(0, 301, 0).getType() == Material.COMMAND_BLOCK, "unsupported tile was replaced");
        world.getBlockAt(0, 301, 0).setType(Material.AIR, false);
        var entity = world.spawnEntity(new Location(world, 2.5, 301, 2.5), EntityType.CREEPER);
        expectRefused(() -> service.enter(farm.id(), creator));
        check(entity.isValid(), "unsupported entity was removed");
        entity.remove();
        check(store.loadActive().isEmpty(), "refusal published an active snapshot");
        check(!world.getPluginChunkTickets().containsKey(this), "mechanism chunk tickets leaked");
        checkEntitySnapshots(world, registry, service, adapter, store, farm.id(), creator, profile, expectedBlocks);
        checkSignSnapshots(world, adapter, resolver);
        ExtendedBlocksCheck.run(this);
    }

    private void checkSignSnapshots(World world, PaperStasisWorld adapter, PaperOutputResolver resolver) throws Exception {
        var registry = new FarmRegistry(FarmRegistry.DEFAULT_MAX_VOLUME);
        var store = new FileSnapshotStore(getDataFolder().toPath().resolve(UUID.randomUUID().toString()), "26.3");
        var service = new StasisService(registry, adapter, store);
        var region = new Cuboid(point(world, 96, 300, 0), point(world, 111, 303, 15));
        for (int x = 96; x <= 111; x++) for (int y = 300; y <= 303; y++) for (int z = 0; z <= 15; z++) {
            world.getBlockAt(x, y, z).setType(y == 300 ? Material.STONE : Material.AIR, false);
        }
        var expectedSigns = new LinkedHashMap<BlockPosition, org.bukkit.block.Sign>();
        var materials = java.util.Arrays.stream(Material.values())
                .filter(material -> !material.name().startsWith("LEGACY_")
                        && material.isBlock() && material.name().endsWith("_SIGN")).toList();
        check(!materials.isEmpty() && materials.size() <= 56, "sign fixture bounds need updating");
        var key = new NamespacedKey(this, "sign_check");
        for (int i = 0; i < materials.size(); i++) {
            var material = materials.get(i);
            int x = 97 + i % 7 * 2;
            int z = 1 + i / 7 * 2;
            var data = getServer().createBlockData(material);
            if (data instanceof org.bukkit.block.data.Directional directional) {
                directional.setFacing(material.name().contains("WALL_HANGING")
                        ? org.bukkit.block.BlockFace.NORTH : org.bukkit.block.BlockFace.EAST);
                world.getBlockAt(x - 1, 301, z).setType(Material.STONE, false);
            } else if (data instanceof org.bukkit.block.data.Rotatable rotatable) {
                rotatable.setRotation(org.bukkit.block.BlockFace.NORTH_EAST);
            }
            if (material.name().contains("HANGING") && !material.name().contains("WALL")) {
                world.getBlockAt(x, 302, z).setType(Material.STONE, false);
            }
            var block = world.getBlockAt(x, 301, z);
            block.setBlockData(data, false);
            var sign = (org.bukkit.block.Sign) block.getState();
            var front = sign.getSide(org.bukkit.block.sign.Side.FRONT);
            var back = sign.getSide(org.bukkit.block.sign.Side.BACK);
            front.line(0, Component.text("Тростник " + i));
            front.line(1, Component.text("Front " + material.name(), net.kyori.adventure.text.format.NamedTextColor.GOLD)
                    .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD));
            front.line(2, Component.empty());
            front.line(3, Component.translatable("block.minecraft.sugar_cane"));
            back.line(0, Component.text("Обратная сторона"));
            back.line(1, Component.text("Back " + i));
            back.line(2, Component.text("   "));
            back.line(3, Component.text("Last line"));
            front.setColor(org.bukkit.DyeColor.RED);
            back.setColor(org.bukkit.DyeColor.CYAN);
            front.setGlowingText(i % 2 == 0);
            back.setGlowingText(i % 2 != 0);
            sign.setWaxed(i % 2 == 0);
            sign.getPersistentDataContainer().set(key, PersistentDataType.STRING, "retained-" + i);
            check(sign.update(true, false), "could not initialize " + material);
            expectedSigns.put(point(world, x, 301, z), (org.bukkit.block.Sign) block.getState());
        }
        var nearby = world.getNearbyEntities(new org.bukkit.util.BoundingBox(95, 299, -1, 113, 305, 17));
        for (var entity : nearby) {
            if (entity instanceof org.bukkit.inventory.InventoryHolder holder) holder.getInventory().clear();
        }
        nearby.forEach(org.bukkit.entity.Entity::remove);
        var output = world.getBlockAt(128, 300, 0);
        output.setType(Material.BARREL, false);
        UUID creator = UUID.randomUUID();
        var farm = registry.register(creator, region, resolver.resolve(output));
        var profile = new ProductionProfile(Map.of(Material.SUGAR_CANE, 1.0), 3600);
        registry.markReady(farm.id(), profile);
        service.enter(farm.id(), creator);
        expectedSigns.keySet().forEach(position -> check(world.getBlockAt(position.x(), position.y(), position.z())
                .getType() == Material.BEDROCK, "sign was not replaced"));
        service.restore(farm.id(), creator);
        assertSigns(world, expectedSigns, key);
        registry.markReady(farm.id(), profile);
        service.enter(farm.id(), creator);
        var recovered = new StasisService(new FarmRegistry(1), adapter, store);
        recovered.recover();
        recovered.restore(farm.id(), creator);
        assertSigns(world, expectedSigns, key);
        check(world.getNearbyEntities(new org.bukkit.util.BoundingBox(95, 299, -1, 113, 305, 17)).isEmpty(), "signs dropped duplicate items");
        getLogger().info("SIGN_STASIS_SMOKE_PASSED: " + expectedSigns.size() + " sign materials");
    }

    private static void assertSigns(World world, Map<BlockPosition, org.bukkit.block.Sign> signs, NamespacedKey key) {
        signs.forEach((position, expected) -> {
            var state = world.getBlockAt(position.x(), position.y(), position.z()).getState();
            check(state instanceof org.bukkit.block.Sign, "sign missing: " + expected.getType());
            var actual = (org.bukkit.block.Sign) state;
            check(expected.getBlockData().equals(actual.getBlockData()), "sign material/orientation changed: " + expected.getType());
            check(expected.isWaxed() == actual.isWaxed(), "sign wax state lost");
            check(java.util.Objects.equals(expected.getPersistentDataContainer().get(key, PersistentDataType.STRING),
                    actual.getPersistentDataContainer().get(key, PersistentDataType.STRING)), "sign PDC lost");
            for (var side : org.bukkit.block.sign.Side.values()) {
                var before = expected.getSide(side);
                var after = actual.getSide(side);
                check(before.lines().equals(after.lines()), "sign text/style lost: " + expected.getType() + " " + side);
                check(before.getColor() == after.getColor(), "sign dye lost");
                check(before.isGlowingText() == after.isGlowingText(), "sign glow lost");
            }
        });
    }

    private void checkEntitySnapshots(World world, FarmRegistry registry, StasisService service,
                                     PaperStasisWorld adapter, FileSnapshotStore store, UUID farmId,
                                     UUID creator, ProductionProfile profile, Map<BlockPosition, String> blocks) throws Exception {
        check(world.getNearbyEntities(new org.bukkit.util.BoundingBox(-2, 298, -2, 10, 310, 10)).isEmpty(), "entity fixture starts with leftover items");
        var originals = new java.util.ArrayList<org.bukkit.entity.Entity>();
        // Cow's centre is outside the cuboid; its bounding box overlaps the last block.
        var cow = (org.bukkit.entity.Cow) world.spawnEntity(new Location(world, 6.2, 301, 2.5), EntityType.COW);
        cow.setAge(-1200);
        originals.add(cow);
        var chicken = (org.bukkit.entity.Chicken) world.spawnEntity(new Location(world, 4.5, 301, 2.5), EntityType.CHICKEN);
        chicken.setAge(-600);
        chicken.setAgeLock(true);
        chicken.setEggLayTime(4321);
        originals.add(chicken);
        var cart = (org.bukkit.entity.Minecart) world.spawnEntity(new Location(world, 6.3, 301, 3.5), EntityType.MINECART);
        originals.add(cart);
        var zombie = (org.bukkit.entity.Zombie) world.spawnEntity(new Location(world, 6.3, 301, 3.5), EntityType.ZOMBIE);
        zombie.setAI(false);
        zombie.setBaby(true);
        check(cart.addPassenger(zombie), "could not set up passenger");
        originals.add(zombie);
        var villager = (org.bukkit.entity.Villager) world.spawnEntity(new Location(world, 2.5, 301, 4.5), EntityType.VILLAGER);
        villager.setAI(false);
        villager.setProfession(org.bukkit.entity.Villager.Profession.FARMER);
        villager.setVillagerLevel(3);
        originals.add(villager);
        var golem = (org.bukkit.entity.IronGolem) world.spawnEntity(new Location(world, 3.5, 301, 4.5), EntityType.IRON_GOLEM);
        golem.setAI(false);
        golem.setPlayerCreated(true);
        originals.add(golem);
        var stand = (org.bukkit.entity.ArmorStand) world.spawnEntity(new Location(world, 1.5, 301, 4.5), EntityType.ARMOR_STAND);
        stand.setSmall(true);
        stand.getEquipment().setHelmet(new ItemStack(Material.DIAMOND_HELMET));
        originals.add(stand);
        var storage = (org.bukkit.entity.minecart.StorageMinecart) world.spawnEntity(new Location(world, 2.5, 301, 3.5), EntityType.CHEST_MINECART);
        storage.getInventory().setItem(0, new ItemStack(Material.EMERALD, 17));
        originals.add(storage);
        var hopper = (org.bukkit.entity.minecart.HopperMinecart) world.spawnEntity(new Location(world, 4.5, 301, 4.5), EntityType.HOPPER_MINECART);
        hopper.setEnabled(false);
        hopper.getInventory().setItem(0, new ItemStack(Material.IRON_INGOT, 11));
        originals.add(hopper);
        var item = world.dropItem(new Location(world, 3.5, 301, 3.5), new ItemStack(Material.DIAMOND, 7));
        item.setPickupDelay(80);
        originals.add(item);
        var locations = new java.util.LinkedHashMap<UUID, Location>();
        var types = new java.util.LinkedHashMap<UUID, EntityType>();
        for (var original : originals) {
            original.setPersistent(true);
            original.customName(Component.text("Entity snapshot " + original.getType()));
            original.getPersistentDataContainer().set(new NamespacedKey(this, "entity_check"), PersistentDataType.STRING, "retained");
            locations.put(original.getUniqueId(), original.getLocation().clone());
            types.put(original.getUniqueId(), original.getType());
        }
        check(!locations.isEmpty(), "empty entity fixture");
        service.enter(farmId, creator);
        for (var id : locations.keySet()) check(getServer().getEntity(id) == null, "original entity remains in stasis: " + id);
        check(world.getNearbyEntities(new org.bukkit.util.BoundingBox(-2, 298, -2, 10, 310, 10)).isEmpty(), "entity removal dropped inventory items");
        assertBedrock(world, blocks);

        var recoveredRegistry = new FarmRegistry(FarmRegistry.DEFAULT_MAX_VOLUME);
        var recovered = new StasisService(recoveredRegistry, adapter, store);
        recovered.recover();
        recovered.restore(farmId, creator);
        assertRestoredEntities(locations, types, cart.getUniqueId(), zombie.getUniqueId());
        check(((org.bukkit.entity.Cow) getServer().getEntity(cow.getUniqueId())).getAge() == -1200, "cow age lost");
        assertChicken(chicken.getUniqueId());
        check(((org.bukkit.entity.Zombie) getServer().getEntity(zombie.getUniqueId())).isBaby(), "zombie baby state lost");
        check(((org.bukkit.entity.Villager) getServer().getEntity(villager.getUniqueId())).getVillagerLevel() == 3, "villager level lost");
        check(((org.bukkit.entity.Villager) getServer().getEntity(villager.getUniqueId())).getProfession() == org.bukkit.entity.Villager.Profession.FARMER, "profession lost");
        check(((org.bukkit.entity.IronGolem) getServer().getEntity(golem.getUniqueId())).isPlayerCreated(), "golem state lost");
        check(((org.bukkit.entity.ArmorStand) getServer().getEntity(stand.getUniqueId())).getEquipment().getHelmet().getType() == Material.DIAMOND_HELMET, "equipment lost");
        check(((org.bukkit.entity.minecart.StorageMinecart) getServer().getEntity(storage.getUniqueId())).getInventory().getItem(0).getAmount() == 17, "cart inventory lost");
        check(!((org.bukkit.entity.minecart.HopperMinecart) getServer().getEntity(hopper.getUniqueId())).isEnabled(), "hopper cart setting lost");
        check(((org.bukkit.entity.Item) getServer().getEntity(item.getUniqueId())).getItemStack().getAmount() == 7, "item entity lost");

        // Fail after spawning complete groups. Retry must remove the failed copies, even outside the cuboid.
        var failureWorld = new StasisWorld() {
            boolean fail = true;
            public boolean hasPlayerInside(Cuboid box) { return adapter.hasPlayerInside(box); }
            public byte[] capture(FarmRegistry.MarkedFarm marked) { return adapter.capture(marked); }
            public void removeEntities(FarmRegistry.MarkedFarm marked, byte[] data) { adapter.removeEntities(marked, data); }
            public void fill(Cuboid box) { adapter.fill(box); }
            public void restore(FarmRegistry.MarkedFarm marked, byte[] data) {
                adapter.restore(marked, data);
                if (fail) { fail = false; throw new IllegalStateException("injected entity restore error"); }
            }
        };
        recoveredRegistry.markReady(farmId, profile);
        var retry = new StasisService(recoveredRegistry, failureWorld, store);
        retry.enter(farmId, creator);
        expectRefused(() -> retry.restore(farmId, creator));
        for (var id : locations.keySet()) check(getServer().getEntity(id) == null, "failed restore left entity copy");
        check(world.getNearbyEntities(new org.bukkit.util.BoundingBox(-2, 298, -2, 10, 310, 10)).isEmpty(), "rollback dropped inventory items");
        assertBedrock(world, blocks);
        retry.restore(farmId, creator);
        assertRestoredEntities(locations, types, cart.getUniqueId(), zombie.getUniqueId());
        assertChicken(chicken.getUniqueId());
        getLogger().info("CHICKEN_STASIS_SMOKE_PASSED: UUID/name/PDC/location/age/age-lock/egg timer; disk recovery and failed-restore retry");
        getLogger().info("ENTITY_STASIS_SMOKE_PASSED");
    }

    private void assertChicken(UUID id) {
        var chicken = (org.bukkit.entity.Chicken) getServer().getEntity(id);
        check(chicken.getAge() == -600 && chicken.getAgeLock(), "chicken age/lock lost");
        check(chicken.getEggLayTime() == 4321, "chicken egg timer lost");
    }

    private void assertRestoredEntities(Map<UUID, Location> locations, Map<UUID, EntityType> types,
                                       UUID vehicleId, UUID passengerId) {
        for (var entry : locations.entrySet()) {
            var entity = getServer().getEntity(entry.getKey());
            check(entity != null && entity.isValid(), "entity UUID not restored: " + entry.getKey());
            check(entity.getType() == types.get(entry.getKey()), "entity type changed");
            check(entity.getLocation().distanceSquared(entry.getValue()) < 0.0001, "entity location changed: " + entity.getType());
            check("retained".equals(entity.getPersistentDataContainer().get(new NamespacedKey(this, "entity_check"), PersistentDataType.STRING)), "entity PDC lost");
            check(Component.text("Entity snapshot " + entity.getType()).equals(entity.customName()), "entity name lost");
        }
        var vehicle = getServer().getEntity(vehicleId);
        check(vehicle.getPassengers().size() == 1 && vehicle.getPassengers().getFirst().getUniqueId().equals(passengerId), "passenger relationship lost");
        check(getServer().getEntity(passengerId).getVehicle().getUniqueId().equals(vehicleId), "vehicle relationship lost");
        var actualIds = vehicle.getWorld().getNearbyEntities(new org.bukkit.util.BoundingBox(-2, 298, -2, 10, 310, 10))
                .stream().map(org.bukkit.entity.Entity::getUniqueId).collect(java.util.stream.Collectors.toSet());
        if (!actualIds.equals(locations.keySet())) {
            var extra = new java.util.HashSet<>(actualIds);
            extra.removeAll(locations.keySet());
            var missing = new java.util.HashSet<>(locations.keySet());
            missing.removeAll(actualIds);
            throw new AssertionError("entity set mismatch: extra=" + extra.stream().map(id -> {
                var entity = getServer().getEntity(id);
                return id + " " + (entity == null ? "missing" : entity.getType() + " " + entity.getLocation()
                        + (entity instanceof org.bukkit.entity.Item dropped ? " " + dropped.getItemStack() : ""));
            }).toList() + ", missing=" + missing);
        }
    }

    private static void assertRestored(World world, Map<BlockPosition, String> blocks, Map<BlockPosition, ItemStack> items) {
        blocks.forEach((position, data) -> check(world.getBlockAt(position.x(), position.y(), position.z())
                .getBlockData().getAsString().equals(data), "block data mismatch at " + position));
        items.forEach((position, item) -> {
            var container = (Container) world.getBlockAt(position.x(), position.y(), position.z()).getState(false);
            var inventory = container instanceof org.bukkit.block.Chest chest ? chest.getBlockInventory() : container.getInventory();
            check(item.equals(inventory.getItem(0)), "item components mismatch at " + position);
        });
    }

    private static void clearTestEntities(World world) {
        var entities = world.getNearbyEntities(new org.bukkit.util.BoundingBox(-2, 298, -2, 10, 310, 10));
        for (var entity : entities) {
            if (entity instanceof org.bukkit.inventory.InventoryHolder holder) holder.getInventory().clear();
        }
        entities.forEach(org.bukkit.entity.Entity::remove);
    }

    private static void assertBedrock(World world, Map<BlockPosition, String> blocks) {
        blocks.keySet().forEach(position -> check(world.getBlockAt(position.x(), position.y(), position.z())
                .getType() == Material.BEDROCK, "incomplete filler at " + position));
    }

    private static void expectRefused(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("expected rejection");
    }

    private static BlockPosition point(World world, int x, int y, int z) {
        return new BlockPosition(world.getUID(), x, y, z);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
