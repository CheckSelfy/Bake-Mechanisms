package io.github.checkselfy.bakemechanisms.profiling;

import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.marking.OutputContainer;
import io.github.checkselfy.bakemechanisms.marking.PaperOutputResolver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Dispenser;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.MarkedFarm;
import java.util.HashSet;

/** Paper adapter for {@link ProfilingWorld}. */
public final class PaperProfilingWorld implements ProfilingWorld {
    private final Plugin plugin;
    private final PaperOutputResolver resolver;

    public PaperProfilingWorld(Plugin plugin, PaperOutputResolver resolver) {
        this.plugin = plugin;
        this.resolver = resolver;
    }

    @Override public ProfilingIsolation.Sources validateIsolation(MarkedFarm farm) {
        var world = plugin.getServer().getWorld(farm.mechanism().min().worldId());
        if (world == null) throw new IllegalStateException("Мир фермы не загружен.");
        requireHopperEvents(world);
        var isolation = new ProfilingIsolation(farm);
        var area = isolation.area();
        for (long x = area.min().x(); x <= area.max().x(); x++) {
            for (long z = area.min().z(); z <= area.max().z(); z++) {
                for (int y = Math.max(world.getMinHeight(), area.min().y()); y <= Math.min(world.getMaxHeight() - 1, area.max().y()); y++) {
                    var block = world.getBlockAt((int) x, y, (int) z);
                    var position = PaperOutputResolver.position(block);
                    if (!farm.mechanism().contains(position) && (block.getType() == Material.HOPPER
                            || block.getType() == Material.DROPPER || block.getType() == Material.DISPENSER)
                            && block.getBlockData() instanceof org.bukkit.block.data.Directional direction) {
                        var destination = PaperOutputResolver.position(block.getRelative(direction.getFacing()));
                        if (farm.mechanism().contains(destination) || isolation.isOutput(destination)) {
                            throw new IllegalStateException("Подающая воронка или выбрасыватель находится вне механизма в "
                                    + x + ", " + y + ", " + z + ". Включите сборщик в кубоид; выход должен быть конечным получателем.");
                        }
                    }
                    if (!(block.getState(false) instanceof InventoryHolder holder)) continue;
                    var inventory = holder.getInventory();
                    for (var viewer : new ArrayList<>(inventory.getViewers())) viewer.closeInventory();
                    if (inventory.getHolder(false) instanceof DoubleChest chest) {
                        for (var side : java.util.List.of(chest.getLeftSide(false), chest.getRightSide(false))) {
                            if (side instanceof org.bukkit.inventory.BlockInventoryHolder half) {
                                var p = PaperOutputResolver.position(half.getBlock());
                                if (!area.contains(p) || farm.mechanism().contains(p) != farm.mechanism().contains(position)
                                        || isolation.isOutput(p) != isolation.isOutput(position)) {
                                    throw new IllegalStateException("Двойной сундук пересекает границу механизма, выхода или защитной области.");
                                }
                            }
                        }
                    }
                    if (farm.mechanism().contains(position)) {
                        if (holder instanceof org.bukkit.loot.Lootable lootable && lootable.getLootTable() != null) {
                            throw new IllegalStateException("Откройте и опустошите внутренний контейнер с добычей перед профилированием.");
                        }
                        boolean shears = holder instanceof Dispenser && java.util.Arrays.stream(inventory.getContents())
                                .allMatch(item -> item == null || item.isEmpty() || item.getType() == Material.SHEARS);
                        if (!inventory.isEmpty() && !shears) throw new IllegalStateException("Опустошите внутренние инвентари перед "
                                + "профилированием: " + block.getType() + " в " + x + ", " + y + ", " + z
                                + ". Начальные запасы нельзя отличить от продукции фермы; в раздатчике разрешены только ножницы.");
                    }
                }
            }
        }
        var actors = new HashSet<UUID>();
        var equipment = new HashSet<Material>();
        for (var entity : world.getNearbyEntities(box(area))) {
            if (farm.mechanism().contains(new io.github.checkselfy.bakemechanisms.marking.BlockPosition(world.getUID(),
                    entity.getLocation().getBlockX(), entity.getLocation().getBlockY(), entity.getLocation().getBlockZ()))) actors.add(entity.getUniqueId());
            if (entity instanceof org.bukkit.entity.LivingEntity living && living.getEquipment() != null) {
                for (var slot : org.bukkit.inventory.EquipmentSlot.values()) {
                    var item = living.getEquipment().getItem(slot);
                    if (item != null && !item.isEmpty()) equipment.add(item.getType());
                }
            }
            if (entity instanceof InventoryHolder holder) {
                for (var viewer : new ArrayList<>(holder.getInventory().getViewers())) viewer.closeInventory();
                // Villager pockets cannot transfer to hoppers and are not dropped on death.
                if (!(entity instanceof Villager) && !holder.getInventory().isEmpty()) {
                    throw new IllegalStateException("Опустошите инвентарь сущности " + entity.getType() + " в защитной области.");
                }
            }
        }
        return new ProfilingIsolation.Sources(actors, equipment);
    }

    private void requireHopperEvents(World world) {
        // The public legacy config API rereads files, not the live inherited world configuration.
        // Read only public members of pinned Paper so an un-reloaded file edit cannot bypass this precondition.
        try {
            var level = world.getClass().getMethod("getHandle").invoke(world);
            var config = level.getClass().getMethod("paperConfig").invoke(level);
            var hopper = config.getClass().getField("hopper").get(config);
            if (hopper.getClass().getField("disableMoveEvent").getBoolean(hopper)) {
                throw new IllegalStateException("Для честного профилирования установите hopper.disable-move-event: false "
                        + "в настройках Paper и перезапустите сервер.");
            }
        } catch (ReflectiveOperationException | LinkageError error) {
            throw new IllegalStateException("Не удалось проверить действующие настройки воронок. Требуется Paper 26.3 build 246.", error);
        }
    }

    /** Normalization keeps only the item type: components, damage and names are dropped. */
    @Override
    public OutputSnapshot readOutput(OutputContainer output) {
        var counts = new HashMap<Material, Integer>();
        boolean full = true;
        for (var item : inventory(output).getStorageContents()) {
            if (item == null || item.isEmpty()) {
                full = false;
                continue;
            }
            counts.merge(item.getType(), item.getAmount(), Integer::sum);
            if (item.getAmount() < item.getMaxStackSize()) full = false;
        }
        return new OutputSnapshot(counts, full);
    }

    /**
     * Live inventory of the registered output, re-resolved on every call.
     *
     * @throws IllegalStateException when the output is no longer the registered container
     */
    public Inventory inventory(OutputContainer output) {
        var anchor = output.blocks().iterator().next();
        World world = plugin.getServer().getWorld(anchor.worldId());
        if (world == null) throw new IllegalStateException("Мир выходного контейнера не загружен.");
        Block block = world.getBlockAt(anchor.x(), anchor.y(), anchor.z());
        try {
            if (!resolver.resolve(block).equals(output)) {
                throw new IllegalStateException("Выходной контейнер изменился или не найден.");
            }
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("Выходной контейнер не найден: " + error.getMessage(), error);
        }
        if (!(block.getState(false) instanceof Container container)) {
            throw new IllegalStateException("Выходной контейнер не найден.");
        }
        return container.getInventory();
    }

    @Override
    public boolean hasPlayerInside(Cuboid mechanism) {
        var world = plugin.getServer().getWorld(mechanism.min().worldId());
        if (world == null) return false;
        var box = box(mechanism);
        return world.getPlayers().stream().anyMatch(player -> player.getBoundingBox().overlaps(box));
    }

    @Override
    public void lockOutput(OutputContainer output) {
        for (var viewer : new ArrayList<>(inventory(output).getViewers())) viewer.closeInventory();
    }

    @Override
    public void addChunkTicket(ChunkPosition chunk) {
        var world = plugin.getServer().getWorld(chunk.worldId());
        if (world == null) throw new IllegalArgumentException("Мир фермы не загружен.");
        world.addPluginChunkTicket(chunk.x(), chunk.z(), plugin);
    }

    @Override
    public void removeChunkTicket(ChunkPosition chunk) {
        var world = plugin.getServer().getWorld(chunk.worldId());
        if (world != null) world.removePluginChunkTicket(chunk.x(), chunk.z(), plugin);
    }

    @Override
    public void notify(UUID playerId, String message) {
        var player = plugin.getServer().getPlayer(playerId);
        if (player != null) player.sendMessage(message);
    }

    /** Block-aligned box of the whole cuboid; touching its faces does not count as overlap. */
    public static BoundingBox box(Cuboid cuboid) {
        var min = cuboid.min();
        var max = cuboid.max();
        return new BoundingBox(min.x(), min.y(), min.z(), max.x() + 1.0, max.y() + 1.0, max.z() + 1.0);
    }
}
