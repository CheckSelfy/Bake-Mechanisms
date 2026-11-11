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
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;

/** Public Paper serialization API, deliberately pinned to 26.3. No NMS or reflection. */
@SuppressWarnings("deprecation")
public final class PaperEntitySnapshots {
    private static final Set<EntityType> SUPPORTED = Set.of(EntityType.COW, EntityType.ZOMBIE,
            EntityType.VILLAGER, EntityType.IRON_GOLEM, EntityType.ARMOR_STAND, EntityType.MINECART,
            EntityType.CHEST_MINECART, EntityType.HOPPER_MINECART, EntityType.ITEM, EntityType.BEE,
            EntityType.CHICKEN);
    private final Plugin plugin;

    public PaperEntitySnapshots(Plugin plugin) { this.plugin = plugin; }

    public List<SnapshotPayload.EntityGroup> capture(World world, Cuboid region) {
        var roots = new LinkedHashMap<UUID, Entity>();
        var bounds = PaperProfilingWorld.box(region);
        for (var entity : world.getNearbyEntities(bounds)) {
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
                if (!entity.isValid() || !entity.isPersistent()) {
                    throw new IllegalStateException("Сущность не может быть сохранена: " + entity.getType());
                }
                if (entity instanceof LivingEntity living && living.isLeashed()) {
                    throw new IllegalStateException("Сначала снимите поводок с сущности " + entity.getType());
                }
                if (entity instanceof InventoryHolder holder && !holder.getInventory().getViewers().isEmpty()) {
                    throw new IllegalStateException("Закройте инвентарь транспорта перед стазисом.");
                }
            }
            var data = plugin.getServer().getUnsafe().serializeEntity(root, EntitySerializationFlag.PASSENGERS);
            result.add(new SnapshotPayload.EntityGroup(root.getUniqueId(), members.stream().map(Entity::getUniqueId).toList(), data));
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

    private static void validateType(Entity entity) {
        if (entity instanceof Player || !SUPPORTED.contains(entity.getType())) {
            throw new IllegalStateException("Нет проверенного сериализатора сущности " + entity.getType());
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
