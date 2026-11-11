package io.github.checkselfy.bakemechanisms.smoke;

import io.github.checkselfy.bakemechanisms.marking.BlockPosition;
import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry;
import io.github.checkselfy.bakemechanisms.marking.PaperOutputResolver;
import io.github.checkselfy.bakemechanisms.profiling.ProductionProfile;
import io.github.checkselfy.bakemechanisms.stasis.FileSnapshotStore;
import io.github.checkselfy.bakemechanisms.stasis.PaperStasisWorld;
import io.github.checkselfy.bakemechanisms.stasis.PaperEntitySnapshots;
import io.github.checkselfy.bakemechanisms.stasis.PaperMobTargets;
import io.github.checkselfy.bakemechanisms.stasis.StasisService;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;

/** Runs in two separate JVMs. Assertions read only live entities and an independent, durable baseline. */
public final class EntityNbtCheck {
    private static final EquipmentSlot[] EQUIPMENT = { EquipmentSlot.HAND, EquipmentSlot.OFF_HAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };
    private final JavaPlugin plugin;
    private final World world;
    private final NamespacedKey key;
    private final Cuboid region;
    private final FileSnapshotStore store;
    private final java.nio.file.Path baseline;

    private EntityNbtCheck(JavaPlugin plugin) {
        this.plugin = plugin;
        world = plugin.getServer().getWorlds().getFirst();
        key = new NamespacedKey(plugin, "nbt_audit");
        region = new Cuboid(point(160, 296, 0), point(239, 319, 79));
        store = new FileSnapshotStore(plugin.getDataFolder().toPath().resolve("entity-audit/farms"), "26.3");
        baseline = plugin.getDataFolder().toPath().resolve("entity-audit/baseline.yml");
    }

    public static void run(JavaPlugin plugin, String phase) throws Exception {
        var check = new EntityNbtCheck(plugin);
        if (phase.equals("capture")) check.capture();
        else if (phase.equals("restore")) check.restore();
        else throw new IllegalArgumentException("Unknown entity audit phase: " + phase);
    }

    private StasisService service(FarmRegistry registry) {
        return new StasisService(registry, new PaperStasisWorld(plugin, new PaperOutputResolver()), store);
    }

    private void capture() throws Exception {
        // This directory belongs exclusively to the destructive test task; allow rerunning failed audits.
        for (var previous : store.loadActive()) store.deactivate(previous.farm().id());
        for (int x = 9; x <= 15; x++) for (int z = -1; z <= 5; z++) world.getChunkAt(x, z).load();
        for (var entity : nearby()) {
            if (entity instanceof InventoryHolder holder) holder.getInventory().clear();
            entity.remove();
        }
        for (int x = 160; x <= 239; x++) for (int y = 296; y <= 319; y++) for (int z = 0; z <= 79; z++) {
            world.getBlockAt(x, y, z).setType(y == 296 ? Material.STONE : Material.AIR, false);
        }
        // Far beyond the anchor chunk's transformer region: regression for the integration with #29.
        world.getBlockAt(239, 296, 79).setType(Material.FARMLAND, false);
        world.getBlockAt(239, 297, 79).setBlockData(plugin.getServer().createBlockData("minecraft:wheat[age=7]"), false);
        var registry = new FarmRegistry(FarmRegistry.DEFAULT_MAX_VOLUME);
        var output = world.getBlockAt(240, 296, 0);
        output.setType(Material.BARREL, false);
        var creator = UUID.randomUUID();
        var farm = registry.register(creator, region, new PaperOutputResolver().resolve(output));
        registry.markReady(farm.id(), new ProductionProfile(Map.of(Material.SUGAR_CANE, 1.0), 3600));
        var entities = fixtures();
        world.getChunkAt(16, 5).load();
        var external = (Cow) world.spawnEntity(new Location(world, 260, 299, 95), EntityType.COW);
        external.setAI(false); external.setPersistent(true); external.setGravity(false);
        var externalSource = (Mob) entities.get(1);
        PaperMobTargets.set(externalSource, external);
        var expected = new YamlConfiguration();
        expected.set("farm", farm.id().toString());
        expected.set("creator", creator.toString());
        expected.set("external-target", external.getUniqueId().toString());
        expected.set("external-source", externalSource.getUniqueId().toString());
        for (var entity : entities) expected.set("entities." + entity.getUniqueId(), state(entity));
        Files.createDirectories(baseline.getParent());
        expected.save(baseline.toFile());
        var service = service(registry);
        service.enter(farm.id(), creator);
        require(external.isValid(), "Stasis removed external target outside the mechanism");
        require(nearby().isEmpty(), "Removal left entities or dropped inventory copies");
        require(world.getBlockAt(160, 296, 0).getType() == Material.BEDROCK, "No filler after enter");
        plugin.getLogger().info("ENTITY_NBT_CAPTURE_PASSED: " + entities.size() + " entities; durable snapshot before shutdown");
    }

    private void restore() throws Exception {
        var expected = new YamlConfiguration();
        expected.load(baseline.toFile());
        var registry = new FarmRegistry(FarmRegistry.DEFAULT_MAX_VOLUME);
        var service = service(registry);
        service.recover();
        var farmId = UUID.fromString(expected.getString("farm"));
        var creator = UUID.fromString(expected.getString("creator"));
        world.getChunkAt(16, 5).load();
        var externalId = UUID.fromString(expected.getString("external-target"));
        var external = plugin.getServer().getEntity(externalId);
        require(external != null && external.isValid(), "External target did not survive clean restart");
        var entries = expected.getConfigurationSection("entities");
        require(entries != null, "Missing independent expected entity state");
        var ids = entries.getKeys(false).stream().map(UUID::fromString).collect(Collectors.toSet());
        require(nearby().isEmpty(), "Entities returned automatically during restart");
        service.restore(farmId, creator);
        assertEntities(expected, ids);
        // Inject the same UUID into the live world while the real original is safely in stasis.
        var original = plugin.getServer().getEntity(ids.iterator().next());
        var conflictingData = plugin.getServer().getUnsafe().serializeEntity(original,
                io.papermc.paper.entity.EntitySerializationFlag.PASSENGERS);
        registry.markReady(farmId, new ProductionProfile(Map.of(Material.SUGAR_CANE, 1.0), 3600));
        service.enter(farmId, creator);
        var conflict = plugin.getServer().getUnsafe().deserializeEntity(conflictingData, world, true, true);
        world.getChunkAt(16, 5).load();
        require(conflict.spawnAt(new Location(world, 260, 299, 90), org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM),
                "Could not initialize UUID conflict probe");
        boolean conflictRejected = false;
        try { service.restore(farmId, creator); } catch (IllegalArgumentException error) {
            conflictRejected = error.getMessage().contains("UUID");
        }
        require(conflictRejected && conflict.isValid(), "UUID conflict deleted an existing entity or allowed duplicate restore");
        require(world.getBlockAt(160, 296, 0).getType() == Material.BEDROCK, "UUID conflict changed the filler");
        removeTree(conflict);
        service.restore(farmId, creator);
        assertEntities(expected, ids);
        // A second complete cycle validates UUID reuse and prevents duplicate groups across restores.
        registry.markReady(farmId, new ProductionProfile(Map.of(Material.SUGAR_CANE, 1.0), 3600));
        service.enter(farmId, creator);
        require(nearby().isEmpty(), "Repeated stasis left duplicates");
        service.restore(farmId, creator);
        assertEntities(expected, ids);
        require(store.loadActive().isEmpty(), "Successful restore left active snapshot");
        rejectionChecks(registry, service, farmId, creator);
        // External references are not part of the snapshot: an obsolete target must not block restoration.
        registry.markReady(farmId, new ProductionProfile(Map.of(Material.SUGAR_CANE, 1.0), 3600));
        service.enter(farmId, creator);
        external.remove();
        service.restore(farmId, creator);
        var sourceId = UUID.fromString(expected.getString("external-source"));
        require(((Mob) plugin.getServer().getEntity(sourceId)).getTarget() == null, "Missing external target was recreated or retained");
        expected.set("entities." + sourceId + ".target-uuid", "null");
        assertEntities(expected, ids);
        plugin.getLogger().info("MOB_TARGET_EXTERNAL_PASSED");
        plugin.getLogger().info("ENTITY_NBT_RESTART_PASSED: " + PaperEntitySnapshots.supportedTypes().size()
                + " entity types, " + ids.size() + " entities, repeated cycle, rejection checks");
    }

    private void assertEntities(YamlConfiguration expected, java.util.Set<UUID> ids) throws java.io.IOException {
        require(world.getBlockAt(239, 297, 79).getType() == Material.WHEAT
                && ((org.bukkit.block.data.Ageable) world.getBlockAt(239, 297, 79).getBlockData()).getAge() == 7,
                "Mature wheat lost at the far corner of a multi-chunk snapshot");
        var actualIds = nearby().stream().map(Entity::getUniqueId).collect(Collectors.toSet());
        require(nearby().size() == ids.size(), "Duplicate entity instances after restore");
        require(actualIds.equals(ids), "Composition/UUID mismatch: expected=" + ids + ", actual=" + actualIds);
        var allDifferences = new ArrayList<String>();
        for (var id : ids) {
            var entity = plugin.getServer().getEntity(id);
            require(entity != null && entity.isValid(), "Missing UUID " + id);
            var before = expected.getConfigurationSection("entities." + id).getValues(false);
            var after = state(entity);
            var differences = new ArrayList<String>();
            before.forEach((property, value) -> {
                boolean same = value.equals(after.get(property));
                if (!same && isItemField(property)) same = decodedItem(value.toString()).equals(decodedItem(after.get(property)));
                if (!same) differences.add(property + ": " + value + " -> " + after.get(property));
            });
            if (!differences.isEmpty()) allDifferences.add(entity.getType() + " " + id + " lost state: " + differences);
        }
        require(allDifferences.isEmpty(), String.join("; ", allDifferences));
    }

    private List<Entity> fixtures() {
        var result = new ArrayList<Entity>();
        var types = new ArrayList<>(List.of(EntityType.COW, EntityType.ZOMBIE, EntityType.VILLAGER, EntityType.IRON_GOLEM,
                EntityType.ARMOR_STAND, EntityType.MINECART, EntityType.CHEST_MINECART,
                EntityType.HOPPER_MINECART, EntityType.ITEM, EntityType.BEE, EntityType.ARROW));
        PaperEntitySnapshots.supportedTypes().stream().sorted().filter(type -> !types.contains(type)).forEach(types::add);
        plugin.getLogger().info("LIVING_AUDIT_TYPES: " + types);
        for (int i = 0; i < types.size(); i++) {
            var type = types.get(i);
            var location = new Location(world, 163 + i % 10 * 7.5, 300, 3 + i / 10 * 7.5, (37 + i * 9) % 180, -17 + i % 30);
            Entity entity = type == EntityType.ITEM ? world.dropItem(location, specialItem())
                    : world.spawnEntity(location, type);
            entity.setRotation(location.getYaw(), location.getPitch());
            entity.setPersistent(true);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setGlowing(true);
            entity.setSilent(true);
            entity.setCustomNameVisible(true);
            entity.customName(Component.text("NBT " + type, net.kyori.adventure.text.format.NamedTextColor.GOLD));
            entity.addScoreboardTag("bake-nbt-audit");
            entity.getPersistentDataContainer().set(key, PersistentDataType.STRING, "entity-" + i);
            entity.getPersistentDataContainer().set(new NamespacedKey(plugin, "nbt_bytes"),
                    PersistentDataType.BYTE_ARRAY, new byte[] { 1, -3, 42 });
            entity.setVelocity(new Vector(0.031, 0.062, -0.015));
            if (entity instanceof Arrow arrow) {
                arrow.setDamage(3.25); arrow.setCritical(true); arrow.setPierceLevel(2);
                arrow.setPickupStatus(AbstractArrow.PickupStatus.ALLOWED);
                arrow.setShooter((LivingEntity) result.getFirst());
                arrow.setBasePotionType(org.bukkit.potion.PotionType.POISON);
            }
            if (entity instanceof LivingEntity living) {
                living.setAI(false);
                living.setRemoveWhenFarAway(false);
                living.setHealth(Math.min(7.5, living.getAttribute(Attribute.MAX_HEALTH).getValue()));
                living.getAttribute(Attribute.SCALE).setBaseValue(1.125);
                living.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 1200, 2, true, false, true));
                if (living instanceof Mob mob) mob.setAware(false);
            }
            if (entity instanceof Ageable ageable && !java.util.Set.of(EntityType.FROG, EntityType.PARROT,
                    EntityType.WANDERING_TRADER, EntityType.CAMEL_HUSK, EntityType.ZOMBIE_NAUTILUS).contains(type)) ageable.setAge(-1234);
            if (entity instanceof Cow cow) { cow.setAgeLock(true); cow.setLoveModeTicks(333); }
            if (entity instanceof Zombie zombie) {
                zombie.setBaby(true);
                zombie.setCanPickupItems(true);
                if (zombie.supportsBreakingDoors()) zombie.setCanBreakDoors(true);
                zombie.setShouldBurnInDay(false);
                zombie.getEquipment().setItemInMainHand(specialItem());
                zombie.getEquipment().setHelmet(specialHelmet());
                zombie.getEquipment().setItemInMainHandDropChance(0.37f);
            }
            if (entity instanceof Villager villager) {
                villager.setProfession(Villager.Profession.FARMER);
                villager.setVillagerType(Villager.Type.DESERT);
                villager.setVillagerLevel(4);
                villager.setVillagerExperience(177);
                villager.setRestocksToday(1);
                var recipe = new MerchantRecipe(specialItem(), 3, 12, true, 7, 0.15f);
                recipe.addIngredient(new ItemStack(Material.EMERALD, 9));
                recipe.setDemand(5); recipe.setSpecialPrice(-2); recipe.setIgnoreDiscounts(true);
                villager.setRecipes(List.of(recipe));
                villager.setMemory(MemoryKey.HOME, new Location(world, 161, 297, 1));
                villager.setMemory(MemoryKey.JOB_SITE, new Location(world, 162, 297, 1));
                villager.setReputation(UUID.fromString("33333333-3333-3333-3333-333333333333"),
                        new com.destroystokyo.paper.entity.villager.Reputation(Map.of(
                                com.destroystokyo.paper.entity.villager.ReputationType.MINOR_POSITIVE, 25)));
            }
            if (entity instanceof IronGolem golem) golem.setPlayerCreated(true);
            if (entity instanceof ArmorStand stand) {
                stand.setSmall(true); stand.setArms(true); stand.setBasePlate(false);
                stand.setHeadPose(new EulerAngle(0.25, -0.5, 0.75));
                stand.setRightArmPose(new EulerAngle(0.5, 0.25, -0.25));
                stand.setLeftArmPose(new EulerAngle(-0.3, 0.4, -0.5));
                stand.setBodyPose(new EulerAngle(0.1, -0.2, 0.3));
                stand.setLeftLegPose(new EulerAngle(0.4, -0.1, 0.2));
                stand.setRightLegPose(new EulerAngle(-0.4, 0.1, -0.2));
                stand.setVisible(false);
                stand.addEquipmentLock(EquipmentSlot.HEAD, ArmorStand.LockType.REMOVING_OR_CHANGING);
                stand.getEquipment().setHelmet(specialHelmet());
                stand.getEquipment().setItemInMainHand(specialItem());
            }
            if (entity instanceof Zombie || entity instanceof ArmorStand) {
                var equipment = ((LivingEntity) entity).getEquipment();
                Material[] materials = { Material.DIAMOND_PICKAXE, Material.DIAMOND_AXE, Material.DIAMOND_HELMET,
                        Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS };
                for (int slot = 0; slot < EQUIPMENT.length; slot++) {
                    equipment.setItem(EQUIPMENT[slot], specialItem().withType(materials[slot]));
                    if (entity instanceof Zombie) equipment.setDropChance(EQUIPMENT[slot], 0.15f + slot * 0.1f);
                }
            }
            if (entity instanceof Minecart cart) {
                cart.setDisplayBlockData(Material.DIAMOND_BLOCK.createBlockData());
                cart.setDisplayBlockOffset(9);
            }
            if (entity instanceof InventoryHolder holder) {
                if (entity instanceof org.bukkit.entity.ChestedHorse horse) horse.setCarryingChest(true);
                if (entity instanceof AbstractHorse horse) {
                    horse.getInventory().setSaddle(new ItemStack(Material.SADDLE));
                    if (horse instanceof Horse ordinary) ordinary.getInventory().setArmor(new ItemStack(Material.DIAMOND_HORSE_ARMOR));
                    if (horse instanceof ChestedHorse) holder.getInventory().setItem(holder.getInventory().getSize() - 1, specialItem());
                } else {
                    holder.getInventory().setItem(0, specialItem());
                    if (holder.getInventory().getSize() > 1) holder.getInventory().setItem(
                            entity instanceof Minecart ? holder.getInventory().getSize() - 1 : 1, new ItemStack(Material.EMERALD, 17));
                }
            }
            if (entity instanceof HopperMinecart hopper) hopper.setEnabled(false);
            if (entity instanceof Item item) {
                item.setPickupDelay(83); item.setUnlimitedLifetime(true);
                item.setOwner(UUID.fromString("11111111-1111-1111-1111-111111111111"));
                item.setThrower(UUID.fromString("22222222-2222-2222-2222-222222222222"));
                item.setCanPlayerPickup(false); item.setHealth(3);
            }
            if (entity instanceof Bee bee) {
                bee.setHasNectar(true); bee.setHasStung(true); bee.setAnger(131);
                bee.setCannotEnterHiveTicks(321); bee.setCropsGrownSincePollination(4);
                bee.setTicksSincePollination(135);
                bee.setHive(new Location(world, 161, 297, 2));
                bee.setFlower(new Location(world, 162, 297, 2));
            }
            configureLiving(entity);
            result.add(entity);
        }
        // Full graph: selection may find either participant, but each UUID must occur once.
        result.get(1).teleport(result.get(5).getLocation());
        result.get(4).teleport(result.get(5).getLocation());
        require(result.get(5).addPassenger(result.get(1)), "Could not mount zombie");
        require(result.get(1).addPassenger(result.get(4)), "Could not create nested passenger graph");
        var adultSheep = (Sheep) world.spawnEntity(new Location(world, 233, 300, 76), EntityType.SHEEP);
        adultSheep.setAdult(); adultSheep.setColor(org.bukkit.DyeColor.RED); adultSheep.setSheared(false);
        adultSheep.setPersistent(true); adultSheep.setAI(false); adultSheep.setAware(false);
        adultSheep.setGravity(false); adultSheep.setInvulnerable(true);
        adultSheep.customName(Component.text("Adult wool producer"));
        adultSheep.getPersistentDataContainer().set(key, PersistentDataType.STRING, "adult-sheep");
        adultSheep.getPersistentDataContainer().set(new NamespacedKey(plugin, "nbt_bytes"), PersistentDataType.BYTE_ARRAY, new byte[] { 42 });
        result.add(adultSheep);
        adultSheep.setInvulnerable(false);
        var alternateTarget = (LivingEntity) result.get(2);
        alternateTarget.setInvulnerable(false);
        int activeTargets = 0;
        for (var entity : result) if (entity instanceof Mob mob) {
            if (mob instanceof Dolphin dolphin) dolphin.setAdult(); // Calves cannot attack in vanilla.
            var target = mob instanceof Breeze ? (LivingEntity) result.get(3)
                    : mob == adultSheep ? alternateTarget : adultSheep;
            target.setInvulnerable(false);
            PaperMobTargets.set(mob, target);
            require(mob.getTarget() != null && mob.getTarget().getUniqueId().equals(target.getUniqueId()),
                    "Could not initialize target for " + entity.getType());
            activeTargets++;
        }
        plugin.getLogger().info("MOB_TARGET_MATRIX: " + activeTargets + " active targets before capture");
        return result;
    }

    private void configureLiving(Entity entity) {
        if (entity instanceof Sheep sheep) { sheep.setColor(org.bukkit.DyeColor.MAGENTA); sheep.setSheared(true); }
        if (entity instanceof Chicken chicken) { chicken.setEggLayTime(12345); chicken.setVariant(Chicken.Variant.COLD); }
        if (entity instanceof Tameable tameable) {
            tameable.setOwner(plugin.getServer().getOfflinePlayer(UUID.fromString("44444444-4444-4444-4444-444444444444")));
            tameable.setTamed(true);
        }
        if (entity instanceof Sittable sit && (entity instanceof Cat || entity instanceof Wolf || entity instanceof Parrot)) sit.setSitting(true);
        if (entity instanceof AbstractHorse horse) { horse.setDomestication(horse.getMaxDomestication() / 2); horse.setJumpStrength(0.83); }
        if (entity instanceof Horse horse) { horse.setColor(Horse.Color.BLACK); horse.setStyle(Horse.Style.WHITE_DOTS); }
        if (entity instanceof Cat cat) { cat.setCatType(Cat.Type.SIAMESE); cat.setCollarColor(org.bukkit.DyeColor.BLUE); }
        if (entity instanceof Wolf wolf) wolf.setCollarColor(org.bukkit.DyeColor.GREEN);
        if (entity instanceof Rabbit rabbit) rabbit.setRabbitType(Rabbit.Type.BLACK_AND_WHITE);
        if (entity instanceof MushroomCow cow) cow.setVariant(MushroomCow.Variant.BROWN);
        if (entity instanceof Goat goat) { goat.setLeftHorn(false); goat.setScreaming(true); }
        if (entity instanceof Panda panda) { panda.setMainGene(Panda.Gene.BROWN); panda.setHiddenGene(Panda.Gene.WEAK); }
        if (entity instanceof Fox fox) fox.setFoxType(Fox.Type.SNOW);
        if (entity instanceof TropicalFish fish) {
            fish.setPattern(TropicalFish.Pattern.BLOCKFISH); fish.setBodyColor(org.bukkit.DyeColor.BLUE); fish.setPatternColor(org.bukkit.DyeColor.YELLOW);
        }
        if (entity instanceof Creeper creeper) { creeper.setPowered(true); creeper.setExplosionRadius(4); creeper.setMaxFuseTicks(45); }
        if (entity instanceof Enderman enderman) enderman.setCarriedBlock(Material.GRASS_BLOCK.createBlockData());
        if (entity instanceof Snowman snowman) snowman.setDerp(true);
        if (entity instanceof CopperGolem copper) copper.setWeatheringState(io.papermc.paper.world.WeatheringCopperState.EXPOSED);
    }

    private Map<String, String> state(Entity entity) throws java.io.IOException {
        var values = new TreeMap<String, String>();
        put(values, "type", entity.getType());
        put(values, "location", location(entity.getLocation()));
        put(values, "velocity", entity.getVelocity());
        put(values, "name", GsonComponentSerializer.gson().serialize(entity.customName()));
        put(values, "pdc-string", entity.getPersistentDataContainer().get(key, PersistentDataType.STRING));
        put(values, "pdc-bytes", java.util.Arrays.toString(entity.getPersistentDataContainer().get(
                new NamespacedKey(plugin, "nbt_bytes"), PersistentDataType.BYTE_ARRAY)));
        put(values, "tags", entity.getScoreboardTags().stream().sorted().toList());
        put(values, "gravity", entity.hasGravity()); put(values, "persistent", entity.isPersistent());
        put(values, "invulnerable", entity.isInvulnerable()); put(values, "silent", entity.isSilent());
        put(values, "glowing", entity.isGlowing()); put(values, "name-visible", entity.isCustomNameVisible());
        put(values, "passengers", entity.getPassengers().stream().map(Entity::getUniqueId).toList());
        put(values, "vehicle", entity.getVehicle() == null ? null : entity.getVehicle().getUniqueId());
        if (entity instanceof Arrow arrow) {
            put(values, "arrow-damage", arrow.getDamage()); put(values, "arrow-critical", arrow.isCritical());
            put(values, "arrow-pierce", arrow.getPierceLevel()); put(values, "arrow-pickup", arrow.getPickupStatus());
            put(values, "arrow-shooter", arrow.getShooter() instanceof Entity source ? source.getUniqueId() : null);
            put(values, "arrow-potion", arrow.getBasePotionType());
        }
        if (entity instanceof LivingEntity living) {
            put(values, "health", living.getHealth()); put(values, "ai", living.hasAI());
            put(values, "scale", living.getAttribute(Attribute.SCALE).getBaseValue());
            put(values, "effects", living.getActivePotionEffects().stream().map(effect -> effect.getType().getKey()
                    + ":" + effect.getAmplifier() + ":" + effect.getDuration() + ":" + effect.isAmbient()
                    + ":" + effect.hasParticles() + ":" + effect.hasIcon()).sorted().toList());
            put(values, "despawn", living.getRemoveWhenFarAway());
            if (living instanceof Mob mob) {
                put(values, "aware", mob.isAware());
                put(values, "target-uuid", mob.getTarget() == null ? null : mob.getTarget().getUniqueId());
            }
            if (living instanceof Zombie || living instanceof ArmorStand) for (var slot : EQUIPMENT) {
                put(values, "item-equipment-" + slot, item(living.getEquipment().getItem(slot)));
                if (living instanceof Zombie) put(values, "drop-" + slot, living.getEquipment().getDropChance(slot));
            }
        }
        if (entity instanceof Ageable ageable) put(values, "age", ageable.getAge());
        if (entity instanceof Cow cow) { put(values, "age-lock", cow.getAgeLock()); put(values, "love-ticks", cow.getLoveModeTicks()); }
        if (entity instanceof Zombie zombie) {
            put(values, "baby", zombie.isBaby()); put(values, "pickup", zombie.getCanPickupItems());
            put(values, "break-doors", zombie.canBreakDoors()); put(values, "burn", zombie.shouldBurnInDay());
            put(values, "main-hand", item(zombie.getEquipment().getItemInMainHand()));
            put(values, "helmet", item(zombie.getEquipment().getHelmet()));
            put(values, "drop-chance", zombie.getEquipment().getItemInMainHandDropChance());
        }
        if (entity instanceof Villager villager) {
            put(values, "profession", villager.getProfession().getKey()); put(values, "variant", villager.getVillagerType().getKey());
            put(values, "level", villager.getVillagerLevel()); put(values, "experience", villager.getVillagerExperience());
            put(values, "restocks", villager.getRestocksToday());
            put(values, "home", location(villager.getMemory(MemoryKey.HOME)));
            put(values, "job", location(villager.getMemory(MemoryKey.JOB_SITE)));
            var reputation = villager.getReputation(UUID.fromString("33333333-3333-3333-3333-333333333333"));
            for (var type : com.destroystokyo.paper.entity.villager.ReputationType.values()) {
                put(values, "reputation-" + type, reputation.getReputation(type));
            }
            var recipes = villager.getRecipes();
            put(values, "recipe-count", recipes.size());
            for (int i = 0; i < recipes.size(); i++) {
                var r = recipes.get(i);
                put(values, "recipe-" + i, r.getUses() + ":" + r.getMaxUses() + ":" + r.getVillagerExperience() + ":" + r.getPriceMultiplier()
                        + ":" + r.getDemand() + ":" + r.getSpecialPrice() + ":" + r.shouldIgnoreDiscounts() + ":" + r.hasExperienceReward());
                put(values, "item-recipe-result-" + i, item(r.getResult()));
                for (int j = 0; j < r.getIngredients().size(); j++) put(values, "item-recipe-ingredient-" + i + "-" + j, item(r.getIngredients().get(j)));
            }
        }
        if (entity instanceof IronGolem golem) put(values, "player-created", golem.isPlayerCreated());
        if (entity instanceof ArmorStand stand) {
            put(values, "small", stand.isSmall()); put(values, "arms", stand.hasArms()); put(values, "base", stand.hasBasePlate());
            put(values, "head-pose", pose(stand.getHeadPose())); put(values, "arm-pose", pose(stand.getRightArmPose()));
            put(values, "left-arm-pose", pose(stand.getLeftArmPose())); put(values, "body-pose", pose(stand.getBodyPose()));
            put(values, "left-leg-pose", pose(stand.getLeftLegPose())); put(values, "right-leg-pose", pose(stand.getRightLegPose()));
            put(values, "visible", stand.isVisible());
            put(values, "head-lock", stand.hasEquipmentLock(EquipmentSlot.HEAD, ArmorStand.LockType.REMOVING_OR_CHANGING));
            put(values, "helmet", item(stand.getEquipment().getHelmet())); put(values, "main-hand", item(stand.getEquipment().getItemInMainHand()));
        }
        if (entity instanceof Minecart cart) {
            put(values, "display", cart.getDisplayBlockData().getAsString()); put(values, "offset", cart.getDisplayBlockOffset());
        }
        if (entity instanceof InventoryHolder holder) {
            for (int i = 0; i < holder.getInventory().getSize(); i++) put(values, "slot-" + i, item(holder.getInventory().getItem(i)));
        }
        if (entity instanceof HopperMinecart hopper) put(values, "hopper-enabled", hopper.isEnabled());
        if (entity instanceof Item item) {
            put(values, "stack", item(item.getItemStack())); put(values, "pickup-delay", item.getPickupDelay());
            put(values, "unlimited", item.isUnlimitedLifetime()); put(values, "owner", item.getOwner()); put(values, "thrower", item.getThrower());
            put(values, "mob-pickup", item.canMobPickup()); put(values, "player-pickup", item.canPlayerPickup()); put(values, "item-health", item.getHealth());
        }
        if (entity instanceof Bee bee) {
            put(values, "nectar", bee.hasNectar()); put(values, "stung", bee.hasStung()); put(values, "anger", bee.getAnger());
            put(values, "hive-delay", bee.getCannotEnterHiveTicks()); put(values, "crops", bee.getCropsGrownSincePollination());
            put(values, "pollination", bee.getTicksSincePollination()); put(values, "sting-time", bee.getTimeSinceSting());
            put(values, "hive", location(bee.getHive())); put(values, "flower", location(bee.getFlower()));
        }
        livingState(entity, values);
        return values;
    }

    private void livingState(Entity entity, Map<String, String> values) {
        if (entity instanceof Sheep sheep) { put(values, "wool", sheep.getColor()); put(values, "sheared", sheep.isSheared()); }
        if (entity instanceof Chicken chicken) { put(values, "egg-timer", chicken.getEggLayTime()); put(values, "chicken-variant", chicken.getVariant().getKey()); }
        if (entity instanceof Tameable tameable) { put(values, "tamed", tameable.isTamed()); put(values, "owner-uuid", tameable.getOwnerUniqueId()); }
        if (entity instanceof Sittable sit) put(values, "sitting", sit.isSitting());
        if (entity instanceof AbstractHorse horse) { put(values, "domestication", horse.getDomestication()); put(values, "jump", horse.getJumpStrength()); }
        if (entity instanceof ChestedHorse horse) put(values, "horse-chest", horse.isCarryingChest());
        if (entity instanceof Horse horse) { put(values, "horse-color", horse.getColor()); put(values, "horse-style", horse.getStyle()); }
        if (entity instanceof Cat cat) { put(values, "cat-type", cat.getCatType().getKey()); put(values, "cat-collar", cat.getCollarColor()); }
        if (entity instanceof Wolf wolf) { put(values, "wolf-variant", wolf.getVariant().getKey()); put(values, "wolf-collar", wolf.getCollarColor()); }
        if (entity instanceof Rabbit rabbit) put(values, "rabbit-type", rabbit.getRabbitType());
        if (entity instanceof MushroomCow cow) put(values, "mooshroom-variant", cow.getVariant());
        if (entity instanceof Goat goat) { put(values, "left-horn", goat.hasLeftHorn()); put(values, "right-horn", goat.hasRightHorn()); put(values, "screaming", goat.isScreaming()); }
        if (entity instanceof Panda panda) { put(values, "main-gene", panda.getMainGene()); put(values, "hidden-gene", panda.getHiddenGene()); }
        if (entity instanceof Fox fox) put(values, "fox-type", fox.getFoxType());
        if (entity instanceof TropicalFish fish) { put(values, "fish-pattern", fish.getPattern()); put(values, "fish-color", fish.getBodyColor()); put(values, "pattern-color", fish.getPatternColor()); }
        if (entity instanceof Creeper creeper) { put(values, "powered", creeper.isPowered()); put(values, "fuse", creeper.getMaxFuseTicks()); put(values, "explosion-radius", creeper.getExplosionRadius()); }
        if (entity instanceof Enderman enderman) put(values, "carried-block", enderman.getCarriedBlock().getAsString());
        if (entity instanceof Snowman snowman) put(values, "snowman-pumpkin", snowman.isDerp());
        if (entity instanceof CopperGolem copper) put(values, "weathering", copper.getWeatheringState());
    }

    private void rejectionChecks(FarmRegistry registry, StasisService service, UUID farmId, UUID creator) throws Exception {
        registry.markReady(farmId, new ProductionProfile(Map.of(Material.SUGAR_CANE, 1.0), 3600));
        var unsupported = world.spawnEntity(new Location(world, 161, 299, 1), EntityType.END_CRYSTAL);
        expectRefused(service, farmId, creator, unsupported);
        unsupported.remove();
        var item = world.dropItem(new Location(world, 161, 299, 1), specialItem());
        item.setCanMobPickup(false);
        expectRefused(service, farmId, creator, item);
        item.remove();
        var cow = (Cow) world.spawnEntity(new Location(world, 161, 299, 1), EntityType.COW);
        cow.setPose(Pose.SITTING, true);
        expectRefused(service, farmId, creator, cow);
        cow.remove();
        var bee = (Bee) world.spawnEntity(new Location(world, 161, 299, 1), EntityType.BEE);
        bee.setTimeSinceSting(113);
        expectRefused(service, farmId, creator, bee);
        bee.setTimeSinceSting(0);
        bee.setRollingOverride(net.kyori.adventure.util.TriState.TRUE);
        expectRefused(service, farmId, creator, bee);
        bee.remove();
        var villager = (Villager) world.spawnEntity(new Location(world, 161, 299, 1), EntityType.VILLAGER);
        villager.getInventory().setItem(7, specialItem());
        expectRefused(service, farmId, creator, villager);
        villager.getInventory().clear();
        villager.getInventory().setItem(0, new ItemStack(Material.BREAD, 3));
        villager.getInventory().setItem(1, new ItemStack(Material.BREAD, 5));
        expectRefused(service, farmId, creator, villager);
        villager.remove();
        var frog = (Frog) world.spawnEntity(new Location(world, 161, 299, 1), EntityType.FROG);
        frog.setAge(-1234);
        expectRefused(service, farmId, creator, frog);
        frog.remove();
        var tameable = (Wolf) world.spawnEntity(new Location(world, 161, 299, 1), EntityType.WOLF);
        tameable.setTamed(true); // Illegal native combination: tamed without an owner.
        expectRefused(service, farmId, creator, tameable);
        tameable.remove();
        var copper = (CopperGolem) world.spawnEntity(new Location(world, 161, 299, 1), EntityType.COPPER_GOLEM);
        copper.setGolemState(CopperGolem.State.GETTING_ITEM);
        expectRefused(service, farmId, creator, copper);
        copper.remove();
        for (int setting = 0; setting < 5; setting++) {
            var cart = (Minecart) world.spawnEntity(new Location(world, 161, 299, 1), EntityType.MINECART);
            switch (setting) {
                case 0 -> cart.setMaxSpeed(cart.getMaxSpeed() + 0.1);
                case 1 -> cart.setSlowWhenEmpty(!cart.isSlowWhenEmpty());
                case 2 -> cart.setFlyingVelocityMod(new Vector(0.1, 0.2, 0.3));
                case 3 -> cart.setDerailedVelocityMod(new Vector(0.1, 0.2, 0.3));
                case 4 -> cart.setDamage(2);
            }
            expectRefused(service, farmId, creator, cart);
            cart.remove();
        }
    }

    private void expectRefused(StasisService service, UUID farmId, UUID creator, Entity unsupported) throws Exception {
        boolean rejected = false;
        try { service.enter(farmId, creator); } catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected && unsupported.isValid(), "Unsupported state/type was not refused before removal: " + unsupported.getType()
                + "; rejected=" + rejected + "; valid=" + unsupported.isValid());
        require(world.getBlockAt(160, 296, 0).getType() == Material.STONE, "Refusal changed blocks");
        require(store.loadActive().isEmpty(), "Refusal published an active snapshot");
    }

    private ItemStack specialItem() {
        var item = new ItemStack(Material.DIAMOND_PICKAXE);
        var meta = item.getItemMeta();
        meta.displayName(Component.text("NBT tool", net.kyori.adventure.text.format.NamedTextColor.AQUA));
        meta.lore(List.of(Component.text("Must survive stasis")));
        meta.addEnchant(Enchantment.UNBREAKING, 3, true);
        ((Damageable) meta).setDamage(37);
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, "item-component");
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack specialHelmet() {
        var item = specialItem().withType(Material.DIAMOND_HELMET);
        return item;
    }

    private static String item(ItemStack item) {
        return item == null || item.isEmpty() ? "empty" : Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    private static boolean isItemField(String field) {
        return field.equals("main-hand") || field.equals("helmet") || field.equals("stack")
                || field.startsWith("slot-") || field.startsWith("item-recipe-") || field.startsWith("item-equipment-");
    }

    private static ItemStack decodedItem(String encoded) {
        return encoded.equals("empty") ? ItemStack.empty() : ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
    }

    private static void put(Map<String, String> map, String name, Object value) { map.put(name, String.valueOf(value)); }
    private static String location(Location l) {
        return l == null ? "null" : l.getWorld().getUID() + ":" + rounded(l.getX()) + ":" + rounded(l.getY()) + ":"
                + rounded(l.getZ()) + ":" + rounded(l.getYaw()) + ":" + rounded(l.getPitch());
    }
    private static String pose(EulerAngle p) { return rounded(p.getX()) + ":" + rounded(p.getY()) + ":" + rounded(p.getZ()); }
    private static long rounded(double value) { return Math.round(value * 10000); }
    private List<Entity> nearby() { return new ArrayList<>(world.getNearbyEntities(new BoundingBox(158, 294, -2, 242, 322, 82))); }
    private BlockPosition point(int x, int y, int z) { return new BlockPosition(world.getUID(), x, y, z); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static void removeTree(Entity entity) {
        for (var passenger : entity.getPassengers()) removeTree(passenger);
        if (entity instanceof InventoryHolder holder) holder.getInventory().clear();
        entity.remove();
    }
}
