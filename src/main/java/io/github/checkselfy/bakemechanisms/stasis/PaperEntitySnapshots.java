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
            if (!entity.getBoundingBox().overlaps(bounds)) continue;
            // A killed mob stays in the world for its death animation, and Paper can expose a just-discarded
            // one until the enclosing tick completes. Loot and experience were dropped in die(), so such a
            // standalone corpse is not part of the mechanism; remove() discards it with the originals.
            if (isCorpse(entity)) continue;
            var root = entity;
            var path = new HashSet<UUID>();
            while (root.getVehicle() != null) {
                if (!path.add(root.getUniqueId())) throw new IllegalStateException("Цикл транспорта в ферме.");
                var vehicle = root.getVehicle();
                if (path.contains(vehicle.getUniqueId())) throw new IllegalStateException("Цикл транспорта в ферме.");
                // Otherwise this rider would be neither in its vehicle's saved tree nor removed with it.
                if (!vehicle.getPassengers().contains(root)) throw transition(root, "садится в транспорт или покидает его");
                root = vehicle;
            }
            roots.putIfAbsent(root.getUniqueId(), root);
        }
        var result = new ArrayList<SnapshotPayload.EntityGroup>();
        for (var root : roots.values()) {
            var members = tree(root);
            // Permanent refusals first: retrying cannot fix an unsupported type or state.
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
            requireStable(root, members);
            // Spawner mobs are commonly non-persistent (not written to chunk files). Paper refuses to serialize them
            // without FORCE; the NBT keeps Bukkit.persist=false, so the restored mob stays non-persistent. FORCE also
            // admits dead or removed entities, which is why every member was checked to be valid above.
            var flags = members.stream().allMatch(Entity::isPersistent)
                    ? new EntitySerializationFlag[] { EntitySerializationFlag.PASSENGERS }
                    : new EntitySerializationFlag[] { EntitySerializationFlag.PASSENGERS, EntitySerializationFlag.FORCE };
            byte[] data;
            try {
                data = plugin.getServer().getUnsafe().serializeEntity(root, flags);
            } catch (IllegalArgumentException changing) {
                // Paper refuses a tree with a member that became invalid during this tick (killed, discarded).
                throw new EntityInTransitionException("Сущность в группе транспорта " + root.getType()
                        + " меняет состояние: " + changing.getMessage());
            }
            if (members.size() > 1 || members.stream().anyMatch(entity -> entity instanceof Minecart || entity instanceof LivingEntity)) {
                // Defaults depend on the native cart implementation/configuration. Compare a detached native load,
                // rather than silently losing settings or hardcoding the defaults of one movement implementation.
                var copy = plugin.getServer().getUnsafe().deserializeEntity(data, world, true, true);
                var copies = new LinkedHashMap<UUID, Entity>();
                for (var member : tree(copy)) copies.put(member.getUniqueId(), member);
                // Validate what restore actually reproduces. Restore spawns the root at its saved position and the
                // vehicle seats every passenger, then re-seats it on each tick from the vehicle's position and body
                // rotation (e.g. a chicken jockey's rider), which NBT does not store. So a passenger's own
                // coordinates are not state: compare the root's exact position and every passenger's vehicle
                // instead. requireStable already proved the live tree consistent in this same tick, so a copy that
                // disagrees is an NBT fidelity problem, not a concurrent change, and retrying cannot fix it.
                for (var member : members) {
                    var restored = copies.get(member.getUniqueId());
                    if (restored == null) {
                        throw new IllegalStateException("NBT не сохраняет сущность " + member.getType()
                                + " в группе транспорта.");
                    }
                    var vehicle = member.getVehicle();
                    if (vehicle == null) {
                        if (member.getLocation().distanceSquared(restored.getLocation()) > 0.00000001) {
                            throw new IllegalStateException("NBT меняет текущую позицию " + member.getType() + ".");
                        }
                    } else if (restored.getVehicle() == null
                            || !restored.getVehicle().getUniqueId().equals(vehicle.getUniqueId())) {
                        throw new IllegalStateException("NBT не сохраняет посадку пассажира " + member.getType()
                                + " на " + vehicle.getType() + ".");
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

    /**
     * Discards the saved originals by UUID (including passengers outside the cuboid) and every corpse left in the
     * cuboid, then proves that no saved original is still alive. Called in the same tick as a successful capture.
     */
    public void remove(World world, Cuboid region, List<SnapshotPayload.EntityGroup> groups) {
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
        // Corpses were skipped by capture; finishing their death animation inside the filler would leave an
        // original behind. Their drops already happened, so discarding them loses nothing.
        var bounds = PaperProfilingWorld.box(region);
        for (var entity : world.getNearbyEntities(bounds)) {
            if (!(entity instanceof Player) && entity.getBoundingBox().overlaps(bounds) && isCorpse(entity)) entity.remove();
        }
        for (var group : groups) for (var id : group.members()) {
            var survivor = plugin.getServer().getEntity(id);
            if (survivor != null && survivor.isValid()) {
                throw new IllegalStateException("Оригинал " + survivor.getType() + " " + id + " не удалён из мира.");
            }
        }
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

    /**
     * Rejects, as retryable, a passenger tree caught between two stable states. Between ticks a lone dying or
     * removed entity is simply skipped (its loot is already dropped), but one still linked to a living vehicle or
     * passenger would be written into, or torn out of, that member's NBT.
     */
    private static void requireStable(List<Entity> members) {
        for (var entity : members) {
            if (!entity.isValid()) {
                throw new EntityInTransitionException("Сущность " + entity.getType() + " в группе транспорта умирает или удаляется.");
            }
            if (!entity.isInWorld()) {
                throw new EntityInTransitionException("Сущность " + entity.getType() + " ещё добавляется в мир.");
            }
            for (var passenger : entity.getPassengers()) {
                var vehicle = passenger.getVehicle();
                if (vehicle == null || !vehicle.getUniqueId().equals(entity.getUniqueId())) {
                    throw new EntityInTransitionException("Пассажир " + passenger.getType() + " садится на "
                            + entity.getType() + " или высаживается.");
                }
            }
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

    /** Dead or already discarded, alone: nothing rides it and it rides nothing. */
    private static boolean isCorpse(Entity entity) {
        return !entity.isValid() && entity.isDead() && entity.getVehicle() == null && entity.getPassengers().isEmpty();
    }

    /** Every live member is in the world and agrees with its vehicle about who rides whom. */
    private static void requireStable(Entity root, List<Entity> members) {
        if (root.getVehicle() != null) throw transition(root, "садится в транспорт или покидает его");
        for (var member : members) {
            if (!member.isValid()) {
                throw transition(member, member.isDead() ? "погибает или удаляется" : "ещё появляется в мире");
            }
            if (!member.isInWorld()) throw transition(member, "ещё добавляется в мир");
            for (var passenger : member.getPassengers()) {
                if (!member.equals(passenger.getVehicle())) {
                    throw transition(passenger, "садится в транспорт или покидает его");
                }
            }
        }
    }

    private static EntityInTransitionException transition(Entity entity, String change) {
        return new EntityInTransitionException("Сущность " + entity.getType() + " " + change + ".");
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
