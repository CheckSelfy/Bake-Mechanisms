package io.github.checkselfy.bakemechanisms.profiling;

import io.github.checkselfy.bakemechanisms.marking.BlockPosition;
import io.github.checkselfy.bakemechanisms.marking.PaperOutputResolver;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import io.papermc.paper.event.entity.EntityMoveEvent;

/** Tracks provenance for one profiling run; no item is deleted or relabelled in persistent world data. */
public final class ProfilingTransportProtection implements Listener {
    private final ProfilingService service;
    public ProfilingTransportProtection(ProfilingService service) { this.service = service; }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTransfer(InventoryMoveItemEvent event) {
        var source = positions(event.getSource());
        var destination = positions(event.getDestination());
        for (var isolation : service.isolations()) {
            boolean touches = isolation.touches(source) || isolation.touches(destination);
            if (!touches) continue;
            if (!isolation.transferAllowed(source, destination)
                    || unknownCarrier(event.getSource(), isolation) || unknownCarrier(event.getDestination(), isolation)) {
                event.setCancelled(true);
                return;
            }
            if (isolation.ambiguous(event.getItem().getType())) {
                event.setCancelled(true);
                service.reject(isolation.farm().id(), "В транспорт попал предмет, совпадающий с начальным снаряжением или инструментами. "
                        + "Уберите исходный запас либо изолируйте его от продукции перед замером.");
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(InventoryPickupItemEvent event) {
        var destination = positions(event.getInventory());
        for (var isolation : service.isolations()) if (isolation.touches(destination)
                && (!isolation.trusted(event.getItem().getUniqueId()) || unknownCarrier(event.getInventory(), isolation))) {
            event.setCancelled(true);
            return;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDrop(PlayerDropItemEvent event) {
        // Paper calls this before ItemSpawnEvent, even when a player throws from outside the protection belt.
        for (var isolation : service.isolations()) isolation.playerDropped(event.getItemDrop().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        var item = event.getEntity();
        var position = position(item);
        for (var isolation : service.isolations()) if (isolation.farm().mechanism().contains(position)) {
            isolation.produced(item.getUniqueId());
            if (isolation.trusted(item.getUniqueId()) && isolation.ambiguous(item.getItemStack().getType())) {
                service.reject(isolation.farm().id(), "Выпал предмет, совпадающий с начальным снаряжением или ножницами. "
                        + "Нельзя отличить запас от продукции; уберите начальный запас и повторите замер.");
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMerge(ItemMergeEvent event) {
        for (var isolation : service.isolations()) if (isolation.trusted(event.getEntity().getUniqueId())
                != isolation.trusted(event.getTarget().getUniqueId())) {
            event.setCancelled(true);
            return;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (event.getEntity() instanceof Item) return;
        for (var isolation : service.isolations()) if (isolation.farm().mechanism().contains(position(event.getEntity()))) {
            isolation.actor(event.getEntity().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityPickup(EntityPickupItemEvent event) {
        for (var isolation : service.isolations()) if ((isolation.hasActor(event.getEntity().getUniqueId())
                || isolation.area().contains(position(event.getEntity()))) && !isolation.trusted(event.getItem().getUniqueId())) {
            event.setCancelled(true);
            return;
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        var source = PaperOutputResolver.position(event.getBlock());
        for (var isolation : service.isolations()) if (isolation.area().contains(source)
                && !isolation.farm().mechanism().contains(source)) {
            if (event.getBlock().getBlockData() instanceof org.bukkit.block.data.Directional direction
                    && isolation.farm().mechanism().contains(PaperOutputResolver.position(event.getBlock().getRelative(direction.getFacing())))) {
                event.setCancelled(true); // Outside dropper cannot create a trusted-looking item just across the boundary.
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVehicleMove(VehicleMoveEvent event) { moved(event.getVehicle(), event.getFrom(), event.getTo()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityMove(EntityMoveEvent event) { moved(event.getEntity(), event.getFrom(), event.getTo()); }

    private void moved(Entity entity, org.bukkit.Location from, org.bukkit.Location to) {
        if (!(entity instanceof InventoryHolder) && !(entity instanceof org.bukkit.entity.LivingEntity)
                || from.getWorld() == null || to.getWorld() == null) return;
        var before = new BlockPosition(from.getWorld().getUID(), from.getBlockX(), from.getBlockY(), from.getBlockZ());
        var after = new BlockPosition(to.getWorld().getUID(), to.getBlockX(), to.getBlockY(), to.getBlockZ());
        for (var isolation : service.isolations()) {
            if (isolation.hasActor(entity.getUniqueId()) && isolation.farm().mechanism().contains(before)
                    && !isolation.farm().mechanism().contains(after)) {
                service.reject(isolation.farm().id(), "Сущность фермы покинула кубоид. Включите весь путь движения в механизм.");
            } else if (!isolation.hasActor(entity.getUniqueId()) && isolation.area().contains(after)) {
                boolean equipped = entity instanceof org.bukkit.entity.LivingEntity living && living.getEquipment() != null
                        && java.util.Arrays.stream(org.bukkit.inventory.EquipmentSlot.values())
                            .map(living.getEquipment()::getItem).anyMatch(item -> item != null && !item.isEmpty());
                if (entity instanceof InventoryHolder holder && !holder.getInventory().isEmpty() || equipped)
                    service.reject(isolation.farm().id(), "В защитную область вошла сущность с посторонним инвентарём или снаряжением.");
                else if (entity instanceof InventoryHolder) isolation.actor(entity.getUniqueId());
            }
        }
    }

    private static boolean unknownCarrier(Inventory inventory, ProfilingIsolation isolation) {
        return inventory.getHolder(false) instanceof Entity entity && !isolation.hasActor(entity.getUniqueId());
    }

    static Set<BlockPosition> positions(Inventory inventory) {
        var holder = inventory.getHolder(false);
        if (holder instanceof BlockInventoryHolder block) return Set.of(PaperOutputResolver.position(block.getBlock()));
        if (holder instanceof DoubleChest chest) {
            var result = new HashSet<BlockPosition>();
            for (var side : java.util.List.of(chest.getLeftSide(false), chest.getRightSide(false))) {
                if (side instanceof BlockInventoryHolder block) result.add(PaperOutputResolver.position(block.getBlock()));
            }
            return Set.copyOf(result);
        }
        if (holder instanceof Entity entity) return Set.of(position(entity));
        return Set.of();
    }

    private static BlockPosition position(Entity entity) {
        var location = entity.getLocation();
        return new BlockPosition(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }
}
