package io.github.checkselfy.bakemechanisms.stasis;

import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.profiling.PaperProfilingWorld;
import io.papermc.paper.entity.EntitySerializationFlag;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Item;
import org.bukkit.entity.Bee;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;

/** Public Paper NBT serialization, with a pinned target-memory adapter for omitted AI references. */
@SuppressWarnings("deprecation")
public final class PaperEntitySnapshots {
    // Explicitly audited against Paper 26.3 build 246; future API types require another native test run.
    private static final Set<EntityType> SUPPORTED = Set.of(
            EntityType.COW, EntityType.ZOMBIE, EntityType.VILLAGER, EntityType.IRON_GOLEM,
            EntityType.ARMOR_STAND, EntityType.MINECART, EntityType.CHEST_MINECART, EntityType.HOPPER_MINECART,
            EntityType.ITEM, EntityType.ARROW, EntityType.BEE, EntityType.ALLAY, EntityType.ARMADILLO,
            EntityType.AXOLOTL, EntityType.BAT, EntityType.BLAZE, EntityType.BOGGED,
            EntityType.BREEZE, EntityType.CAMEL, EntityType.CAMEL_HUSK, EntityType.CAT,
            EntityType.CAVE_SPIDER, EntityType.CHICKEN, EntityType.COD, EntityType.COPPER_GOLEM,
            EntityType.CREAKING, EntityType.CREEPER, EntityType.DOLPHIN, EntityType.DONKEY,
            EntityType.DROWNED, EntityType.ELDER_GUARDIAN, EntityType.ENDERMAN, EntityType.ENDERMITE,
            EntityType.EVOKER, EntityType.FOX, EntityType.FROG, EntityType.GHAST,
            EntityType.GIANT, EntityType.GLOW_SQUID, EntityType.GOAT, EntityType.GUARDIAN,
            EntityType.HAPPY_GHAST, EntityType.HOGLIN, EntityType.HORSE, EntityType.HUSK,
            EntityType.ILLUSIONER, EntityType.LLAMA, EntityType.MAGMA_CUBE, EntityType.MANNEQUIN,
            EntityType.MOOSHROOM, EntityType.MULE, EntityType.NAUTILUS, EntityType.OCELOT,
            EntityType.PANDA, EntityType.PARCHED, EntityType.PARROT, EntityType.PHANTOM,
            EntityType.PIG, EntityType.PIGLIN, EntityType.PIGLIN_BRUTE, EntityType.PILLAGER,
            EntityType.POLAR_BEAR, EntityType.PUFFERFISH, EntityType.RABBIT, EntityType.RAVAGER,
            EntityType.SALMON, EntityType.SHEEP, EntityType.SHULKER, EntityType.SILVERFISH,
            EntityType.SKELETON, EntityType.SKELETON_HORSE, EntityType.SLIME, EntityType.SNIFFER,
            EntityType.SNOW_GOLEM, EntityType.SPIDER, EntityType.SQUID, EntityType.STRAY,
            EntityType.STRIDER, EntityType.SULFUR_CUBE, EntityType.TADPOLE, EntityType.TRADER_LLAMA,
            EntityType.TROPICAL_FISH, EntityType.TURTLE, EntityType.VEX, EntityType.VINDICATOR,
            EntityType.WANDERING_TRADER, EntityType.WARDEN, EntityType.WITCH, EntityType.WITHER,
            EntityType.WITHER_SKELETON, EntityType.WOLF, EntityType.ZOGLIN, EntityType.ZOMBIE_HORSE,
            EntityType.ZOMBIE_NAUTILUS, EntityType.ZOMBIE_VILLAGER, EntityType.ZOMBIFIED_PIGLIN);
    private final Plugin plugin;

    public PaperEntitySnapshots(Plugin plugin) { this.plugin = plugin; }

    public static Set<EntityType> supportedTypes() { return SUPPORTED; }

    public List<SnapshotPayload.EntityGroup> capture(World world, Cuboid region) {
        var roots = new LinkedHashMap<UUID, Entity>();
        var bounds = PaperProfilingWorld.box(region);
        for (var entity : world.getNearbyEntities(bounds)) {
            // Paper can expose a just-discarded mob until the enclosing tick completes. It no longer
            // belongs to the world and must not make a whole farm snapshot fail.
            if (!entity.isValid()) continue;
            if (!entity.getBoundingBox().overlaps(bounds)) continue;
            var root = entity;
            var path = new HashSet<UUID>();
            while (root.getVehicle() != null) {
                if (!path.add(root.getUniqueId())) throw new IllegalStateException("Цикл транспорта в ферме.");
                root = root.getVehicle();
            }
            roots.putIfAbsent(root.getUniqueId(), root);
        }
        var result = new ArrayList<SnapshotPayload.EntityGroup>();
        for (var root : roots.values()) {
            var members = tree(root);
            for (var entity : members) {
                validateType(entity);
                validateRuntimeState(entity);
                if (entity instanceof LivingEntity living && living.isLeashed()) {
                    throw new IllegalStateException("Сначала снимите поводок с сущности " + entity.getType());
                }
                if (entity instanceof InventoryHolder holder && !holder.getInventory().getViewers().isEmpty()) {
                    throw new IllegalStateException("Закройте инвентарь транспорта перед стазисом.");
                }
            }
            var data = plugin.getServer().getUnsafe().serializeEntity(root, EntitySerializationFlag.PASSENGERS);
            if (members.size() > 1 || members.stream().anyMatch(entity -> entity instanceof Minecart || entity instanceof LivingEntity)) {
                // Defaults depend on the native cart implementation/configuration. Compare a detached native load,
                // rather than silently losing settings or hardcoding the defaults of one movement implementation.
                var copy = plugin.getServer().getUnsafe().deserializeEntity(data, world, true, true);
                var copies = new LinkedHashMap<UUID, Entity>();
                for (var member : tree(copy)) copies.put(member.getUniqueId(), member);
                for (var member : members) if (member.getVehicle() != null) {
                    var restored = copies.get(member.getUniqueId());
                    if (restored == null || member.getLocation().distanceSquared(restored.getLocation()) > 0.00000001) {
                        throw new IllegalStateException("NBT меняет текущую позицию пассажира " + member.getType()
                                + ". Дождитесь завершения посадки или высадите пассажира перед стазисом.");
                    }
                }
                for (var member : members) if (member instanceof InventoryHolder holder) {
                    if (!(copies.get(member.getUniqueId()) instanceof InventoryHolder restored)
                            || !java.util.Arrays.equals(holder.getInventory().getContents(), restored.getInventory().getContents())) {
                        throw new IllegalStateException("NBT меняет, уплотняет или объединяет предметы в инвентаре " + member.getType() + ". "
                                + "Уберите пустые промежутки и объедините совместимые стеки перед стазисом.");
                    }
                }
                for (var member : members) if (member instanceof org.bukkit.entity.Tameable original) {
                    if (!(copies.get(member.getUniqueId()) instanceof org.bukkit.entity.Tameable restored)
                            || original.isTamed() != restored.isTamed()
                            || !java.util.Objects.equals(original.getOwnerUniqueId(), restored.getOwnerUniqueId())) {
                        throw new IllegalStateException("NBT меняет приручение или хозяина " + member.getType()
                                + ". Назначьте согласованное состояние приручения и хозяина перед стазисом.");
                    }
                }
                for (var member : members) if (member instanceof org.bukkit.entity.Sittable original) {
                    if (!(copies.get(member.getUniqueId()) instanceof org.bukkit.entity.Sittable restored)
                            || original.isSitting() != restored.isSitting()) {
                        throw new IllegalStateException("NBT не сохраняет текущую сидячую позу " + member.getType());
                    }
                }
                for (var member : members) if (member instanceof org.bukkit.entity.Ageable original) {
                    if (!(copies.get(member.getUniqueId()) instanceof org.bukkit.entity.Ageable restored)
                            || original.getAge() != restored.getAge()) {
                        throw new IllegalStateException("NBT не сохраняет текущий возраст " + member.getType()
                                + ". Этот вид не поддерживает заданное состояние возраста.");
                    }
                }
                for (var member : members) if (member instanceof Minecart cart) {
                    if (!(copies.get(member.getUniqueId()) instanceof Minecart restored)) {
                        throw new IllegalStateException("Не удалось проверить настройки вагонетки.");
                    }
                    if (Double.compare(cart.getMaxSpeed(), restored.getMaxSpeed()) != 0
                            || cart.isSlowWhenEmpty() != restored.isSlowWhenEmpty()
                            || !cart.getFlyingVelocityMod().equals(restored.getFlyingVelocityMod())
                            || !cart.getDerailedVelocityMod().equals(restored.getDerailedVelocityMod())
                            || Double.compare(cart.getDamage(), restored.getDamage()) != 0) {
                        throw new IllegalStateException("NBT не сохраняет текущие настройки движения или повреждение вагонетки "
                                + cart.getType() + ". Верните стандартные настройки и дождитесь сброса повреждения.");
                    }
                }
                for (var member : members) if (member instanceof Mob mob && mob.getTarget() != null) {
                    if (!(copies.get(member.getUniqueId()) instanceof Mob copyMob)) {
                        throw new IllegalStateException("Источник цели не является мобом в снимке.");
                    }
                    // Test compatibility on the detached copy before publishing a snapshot or removing originals.
                    PaperMobTargets.set(copyMob, mob.getTarget());
                }
            }
            var targets = new LinkedHashMap<UUID, UUID>();
            for (var member : members) if (member instanceof Mob mob && mob.getTarget() != null) {
                targets.put(member.getUniqueId(), mob.getTarget().getUniqueId());
            }
            result.add(new SnapshotPayload.EntityGroup(root.getUniqueId(), members.stream().map(Entity::getUniqueId).toList(), data, targets));
        }
        return List.copyOf(result);
    }

    /** Validates complete trees before any entity is spawned or block is restored. */
    public List<Entity> prepare(World world, List<SnapshotPayload.EntityGroup> groups) {
        var result = new ArrayList<Entity>();
        for (var group : groups) {
            var root = plugin.getServer().getUnsafe().deserializeEntity(group.data(), world, true, true);
            var members = tree(root);
            members.forEach(PaperEntitySnapshots::validateType);
            if (!root.getUniqueId().equals(group.rootId())
                    || !new HashSet<>(group.members()).equals(new HashSet<>(members.stream().map(Entity::getUniqueId).toList()))) {
                throw new IllegalStateException("Состав группы сущностей не совпадает со снимком.");
            }
            for (var source : group.targets().keySet()) {
                if (members.stream().noneMatch(entity -> entity.getUniqueId().equals(source) && entity instanceof Mob)) {
                    throw new IllegalStateException("Источник сохранённой цели не является мобом в снимке.");
                }
            }
            result.add(root);
        }
        return List.copyOf(result);
    }

    public void remove(List<SnapshotPayload.EntityGroup> groups) {
        var members = new ArrayList<Entity>();
        for (var group : groups) for (var id : group.members()) {
            var entity = plugin.getServer().getEntity(id);
            if (entity instanceof Player) throw new IllegalStateException("Нельзя удалить игрока из снимка.");
            if (entity != null) members.add(entity);
        }
        // Discarding a container minecart also drops its inventory on Paper.
        // The snapshot is already durable before this method is called.
        for (var entity : members) {
            if (entity instanceof InventoryHolder holder) holder.getInventory().clear();
        }
        members.forEach(Entity::remove);
    }

    public void spawn(List<Entity> roots) {
        for (var root : roots) {
            if (!root.spawnAt(root.getLocation(), SpawnReason.CUSTOM)) {
                throw new IllegalStateException("Сервер отклонил восстановление " + root.getType());
            }
            for (var member : tree(root)) {
                if (!member.isValid()) throw new IllegalStateException("Не восстановлен пассажир " + member.getType());
            }
        }
    }

    /** Reconnect only after every group has spawned, including targets from other passenger trees. */
    public void restoreTargets(List<Entity> roots, List<SnapshotPayload.EntityGroup> groups) {
        var restored = new LinkedHashMap<UUID, Entity>();
        for (var root : roots) for (var member : tree(root)) restored.put(member.getUniqueId(), member);
        for (var group : groups) for (var link : group.targets().entrySet()) {
            if (!(restored.get(link.getKey()) instanceof Mob mob)) {
                throw new IllegalStateException("Источник сохранённой цели не является восстановленным мобом.");
            }
            var target = restored.containsKey(link.getValue()) ? restored.get(link.getValue())
                    : plugin.getServer().getEntity(link.getValue());
            if (!(target instanceof LivingEntity living) || !living.isValid() || living.isDead()
                    || living.isInvulnerable() || !living.getWorld().equals(mob.getWorld())
                    || living instanceof Player player && (!player.isOnline()
                        || player.getGameMode() == org.bukkit.GameMode.CREATIVE
                        || player.getGameMode() == org.bukkit.GameMode.SPECTATOR)) {
                if (restored.containsKey(link.getValue())) {
                    throw new IllegalStateException("Цель внутри снимка не восстановлена или недоступна: " + link.getValue());
                }
                // External targets may die, log out, unload or change dimension while the farm is in stasis.
                // Never recreate/teleport them or keep the whole farm in stasis because of an obsolete reference.
                PaperMobTargets.set(mob, null);
                plugin.getLogger().info("Цель " + link.getValue() + " моба " + link.getKey()
                        + " недоступна; после восстановления AI выберет новую цель.");
                continue;
            }
            PaperMobTargets.set(mob, living);
        }
    }

    private static void validateType(Entity entity) {
        if (entity instanceof Player || !SUPPORTED.contains(entity.getType())) {
            throw new IllegalStateException("Нет проверенного сериализатора сущности " + entity.getType());
        }
    }

    private static void validateRuntimeState(Entity entity) {
        if (entity instanceof org.bukkit.entity.Creaking creaking && (creaking.getHome() != null || creaking.isActive())) {
            throw new IllegalStateException("Скрипун связан с сердцем или активен. Такая связь не входит в снимок фермы.");
        }
        if (entity instanceof org.bukkit.entity.CopperGolem golem
                && golem.getGolemState() != org.bukkit.entity.CopperGolem.State.IDLE) {
            throw new IllegalStateException("Дождитесь завершения работы медного голема с контейнером перед стазисом.");
        }
        if (entity.hasFixedPose()) {
            throw new IllegalStateException("NBT не сохраняет принудительно закреплённую позу " + entity.getType()
                    + ". Снимите фиксацию позы перед стазисом.");
        }
        if (entity instanceof LivingEntity living && !living.getCollidableExemptions().isEmpty()) {
            throw new IllegalStateException("NBT не сохраняет индивидуальные исключения столкновений " + entity.getType());
        }
        if (entity instanceof Item item && !item.canMobPickup()) {
            throw new IllegalStateException("NBT не сохраняет запрет подбора предмета мобами. Уберите такой предмет перед стазисом.");
        }
        if (entity instanceof Minecart && entity instanceof org.bukkit.loot.Lootable lootable && lootable.getLootTable() != null) {
            throw new IllegalStateException("Сначала откройте вагонетку с несгенерированной добычей.");
        }
        if (entity instanceof Bee bee && (bee.getTimeSinceSting() != 0
                || bee.getRollingOverride() != net.kyori.adventure.util.TriState.NOT_SET)) {
            throw new IllegalStateException("NBT не сохраняет текущий таймер после ужаления или принудительный крен пчелы. "
                    + "Уберите такую пчелу из механизма перед стазисом.");
        }
    }

    private static List<Entity> tree(Entity root) {
        var result = new ArrayList<Entity>();
        collect(root, new HashSet<>(), result);
        return result;
    }

    private static void collect(Entity entity, Set<UUID> visited, List<Entity> result) {
        if (!visited.add(entity.getUniqueId())) throw new IllegalStateException("Повтор сущности в группе транспорта.");
        result.add(entity);
        for (var passenger : entity.getPassengers()) collect(passenger, visited, result);
    }
}
