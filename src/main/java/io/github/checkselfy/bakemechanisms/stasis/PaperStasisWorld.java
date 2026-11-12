package io.github.checkselfy.bakemechanisms.stasis;

import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.MarkedFarm;
import io.github.checkselfy.bakemechanisms.marking.PaperOutputResolver;
import io.github.checkselfy.bakemechanisms.profiling.PaperProfilingWorld;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;
import java.util.Set;
import java.util.ArrayList;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.Skull;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.Beehive;
import org.bukkit.block.Furnace;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.loot.Lootable;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BlockVector;

/** Native structure serialization preserves block data, tile NBT and item components without NMS. */
public final class PaperStasisWorld implements StasisWorld {
    private static final Set<Material> SUPPORTED_TILES = Set.of(Material.CHEST, Material.BARREL,
            Material.HOPPER, Material.DISPENSER, Material.DROPPER, Material.FURNACE, Material.BLAST_FURNACE,
            Material.SMOKER, Material.CRAFTER, Material.SPAWNER, Material.BEACON, Material.BEEHIVE,
            Material.BEE_NEST, Material.LECTERN, Material.BREWING_STAND);
    private final Plugin plugin;
    private final PaperOutputResolver resolver;
    private final PaperEntitySnapshots entities;
    private final OfflinePlayerSafety players;

    public PaperStasisWorld(Plugin plugin, PaperOutputResolver resolver) {
        this(plugin, resolver, new OfflinePlayerSafety(plugin.getServer()));
    }

    public PaperStasisWorld(Plugin plugin, PaperOutputResolver resolver, OfflinePlayerSafety players) {
        this.plugin = plugin;
        this.resolver = resolver;
        this.entities = new PaperEntitySnapshots(plugin);
        this.players = players;
    }

    @Override public boolean hasPlayerInside(Cuboid mechanism) {
        var world = world(mechanism);
        var box = PaperProfilingWorld.box(mechanism);
        return world.getPlayers().stream().anyMatch(player -> player.getBoundingBox().overlaps(box))
                || players.hasPlayerInside(mechanism);
    }

    @Override public byte[] capture(MarkedFarm farm) {
        var region = farm.mechanism();
        var world = world(region);
        loadChunks(world, region);
        // A chunk's blocks can be loaded while its entities are still being read: such entities would be missed by
        // the snapshot and survive inside the filler next to their restored copies.
        for (int x = (region.min().x() >> 4) - 1; x <= (region.max().x() >> 4) + 1; x++) {
            for (int z = (region.min().z() >> 4) - 1; z <= (region.max().z() >> 4) + 1; z++) {
                if (!world.getChunkAt(x, z).isEntitiesLoaded()) {
                    throw new EntityInTransitionException("Сущности чанка " + x + ", " + z + " ещё загружаются.");
                }
            }
        }
        var groups = entities.capture(world, region);
        var voidBlocks = new ArrayList<SnapshotPayload.VoidBlock>();
        for (var output : farm.outputs()) {
            var anchor = output.blocks().iterator().next();
            if (!resolver.resolve(world.getBlockAt(anchor.x(), anchor.y(), anchor.z())).equals(output)) {
                throw new IllegalStateException("Выходной контейнер изменился.");
            }
        }
        eachBlock(world, region, block -> {
            var state = block.getState();
            // Structure placement cannot reconstruct the moving block entity through public Paper API.
            // Commands wait for the stroke to finish; never publish a lossy in-motion snapshot.
            if (block.getType() == Material.MOVING_PISTON) throw new PistonInMotionException();
            if (state instanceof TileState && !(state instanceof Sign) && !(state instanceof Skull)
                    && !(state instanceof ShulkerBox) && !SUPPORTED_TILES.contains(block.getType())) {
                throw new IllegalStateException("Нет проверенного сериализатора блока " + block.getType()
                        + " в " + block.getX() + ", " + block.getY() + ", " + block.getZ());
            }
            if (block.getType() == Material.STRUCTURE_VOID) {
                voidBlocks.add(new SnapshotPayload.VoidBlock(block.getX() - region.min().x(),
                        block.getY() - region.min().y(), block.getZ() - region.min().z()));
            }
            if (state instanceof Lootable lootable && lootable.getLootTable() != null) {
                throw new IllegalStateException("Сначала откройте контейнер с несгенерированной добычей.");
            }
            if (state instanceof InventoryHolder holder && !holder.getInventory().getViewers().isEmpty()) {
                throw new IllegalStateException("Закройте внутренние контейнеры перед стазисом.");
            }
            // A double chest crossing the boundary would retain a live half outside the snapshot.
            if (block.getType() == Material.CHEST) {
                var chest = resolver.resolve(block);
                if (chest.blocks().stream().anyMatch(position -> !region.contains(position))) {
                    throw new IllegalStateException("Внутренний двойной сундук пересекает границу механизма.");
                }
            }
        });
        var structure = plugin.getServer().getStructureManager().createStructure();
        var min = region.min();
        var max = region.max();
        structure.fill(new Location(world, min.x(), min.y(), min.z()),
                new BlockVector(max.x() - min.x() + 1, max.y() - min.y() + 1, max.z() - min.z() + 1), false);
        try (var bytes = new ByteArrayOutputStream()) {
            plugin.getServer().getStructureManager().saveStructure(bytes, structure);
            return new SnapshotPayload(bytes.toByteArray(), groups, voidBlocks).encode();
        } catch (IOException error) {
            throw new IllegalStateException("Не удалось сериализовать снимок.", error);
        }
    }

    @Override public void fill(Cuboid mechanism) {
        fill(mechanism,FillerStyle.DEFAULT);
    }

    @Override public void fill(Cuboid mechanism,FillerStyle style) {
        var world = world(mechanism);
        loadChunks(world, mechanism);
        // Paper drops live container contents on replacement, even with physics=false.
        // This is called only after the complete snapshot has been saved successfully.
        eachBlock(world, mechanism, block -> {
            var state = block.getState(false);
            if (state instanceof InventoryHolder holder) holder.getInventory().clear();
            // Furnace removal pays out stored recipe XP; hive removal can release its bees.
            // Both are already in the saved snapshot and must not also appear in the live world.
            if (state instanceof Furnace furnace) {
                furnace.setRecipesUsed(java.util.Map.of());
                if (!furnace.update(true, false)) throw new IllegalStateException("Не удалось очистить опыт печи.");
            }
            if (state instanceof Beehive hive) {
                hive.clearEntities();
                if (!hive.update(true, false)) throw new IllegalStateException("Не удалось подготовить улей.");
            }
        });
        var filler=new SlimeFiller(mechanism,style);
        var min=mechanism.min();
        eachBlock(world, mechanism, block -> block.setType(filler.material((long)block.getX()-min.x(),
                (long)block.getY()-min.y(),(long)block.getZ()-min.z()), false));
    }

    @Override public void validateRestore(MarkedFarm farm, byte[] data) {
        for (var group : SnapshotPayload.decode(data).groups()) for (var id : group.members()) {
            if (plugin.getServer().getEntity(id) != null) {
                throw new IllegalArgumentException("Восстановление не начато: UUID сущности " + id
                        + " уже существует в мире. Устраните конфликт перед повторной командой.");
            }
        }
    }

    @Override public void restore(MarkedFarm farm, byte[] data) {
        validateRestore(farm, data);
        var region = farm.mechanism();
        var world = world(region);
        loadChunks(world, region);
        var payload = SnapshotPayload.decode(data);
        var restoredEntities = entities.prepare(world, payload.groups());
        try (var input = new ByteArrayInputStream(payload.blocks())) {
            var structure = plugin.getServer().getStructureManager().loadStructure(input);
            var expected = new BlockVector(region.max().x() - region.min().x() + 1,
                    region.max().y() - region.min().y() + 1, region.max().z() - region.min().z() + 1);
            if (!structure.getSize().equals(expected) || structure.getEntityCount() != 0) {
                throw new IllegalStateException("Размер или состав снимка не соответствует блочной ферме.");
            }
            var min = region.min();
            for (var block : payload.voidBlocks()) {
                if (block.x() >= expected.getBlockX() || block.y() >= expected.getBlockY() || block.z() >= expected.getBlockZ()) {
                    throw new IllegalStateException("Технический блок за пределами снимка.");
                }
            }
            if (structure.getPaletteCount() > 0) {
                // Paper's transformer accessor is limited to the anchor chunk and its neighbours.
                // Prepare the complete plain support geometry first, then crops, without physics.
                // Native placement sees their final states and still owns all tile NBT.
                var savedBlocks = structure.getPalettes().getFirst().getBlocks();
                for (boolean growth : new boolean[] { false, true }) for (var saved : savedBlocks) {
                    if (saved instanceof TileState || (saved.getBlockData() instanceof Ageable) != growth) continue;
                    world.getBlockAt(min.x() + saved.getX(), min.y() + saved.getY(), min.z() + saved.getZ())
                            .setBlockData(saved.getBlockData(), false);
                }
                structure.place(new Location(world, min.x(), min.y(), min.z()), false,
                        StructureRotation.NONE, Mirror.NONE, 0, 1.0f, new Random(0));
                // Native placement can reject support-dependent blocks while the rest of the
                // template is still filler (e.g. mature wheat above farmland becomes air).
                // Once the complete support geometry is back, enforce the saved plain block
                // states without physics. Tile NBT remains owned by native placement.
                for (var saved : structure.getPalettes().getFirst().getBlocks()) {
                    if (saved instanceof TileState) continue;
                    var target = world.getBlockAt(min.x() + saved.getX(), min.y() + saved.getY(), min.z() + saved.getZ());
                    if (!target.getBlockData().equals(saved.getBlockData())) target.setBlockData(saved.getBlockData(), false);
                }
            } else if (payload.voidBlocks().size() != (long) expected.getBlockX() * expected.getBlockY() * expected.getBlockZ()) {
                throw new IllegalStateException("В снимке отсутствует палитра блоков.");
            }
            // Native structure templates intentionally omit structure_void; restore its exact cells explicitly.
            for (var block : payload.voidBlocks()) {
                world.getBlockAt(min.x() + block.x(), min.y() + block.y(), min.z() + block.z())
                        .setType(Material.STRUCTURE_VOID, false);
            }
            entities.spawn(restoredEntities);
            entities.restoreTargets(restoredEntities, payload.groups());
        } catch (IOException error) {
            throw new IllegalStateException("Не удалось прочитать снимок.", error);
        }
    }

    @Override public void removeEntities(MarkedFarm farm, byte[] data) {
        entities.remove(SnapshotPayload.decode(data).groups());
    }

    @Override public void verifyHandoff(MarkedFarm farm, byte[] data) {
        for (var group : SnapshotPayload.decode(data).groups()) for (var id : group.members()) {
            var original = plugin.getServer().getEntity(id);
            if (original != null && original.isValid()) {
                throw new IllegalStateException("Оригинал сущности " + original.getType() + " " + id + " не удалён.");
            }
        }
        var world = world(farm.mechanism());
        for (var entity : world.getNearbyEntities(PaperProfilingWorld.box(farm.mechanism()))) {
            if (entity.isValid() && !(entity instanceof org.bukkit.entity.Player)) {
                throw new IllegalStateException("После замены в механизме осталась сущность " + entity.getType()
                        + " " + entity.getUniqueId() + ", которой нет в снимке.");
            }
        }
    }

    private World world(Cuboid region) {
        var world = plugin.getServer().getWorld(region.min().worldId());
        if (world == null) throw new IllegalStateException("Мир фермы не загружен.");
        if (region.min().y() < world.getMinHeight() || region.max().y() >= world.getMaxHeight()) {
            throw new IllegalStateException("Высота мира изменилась; восстановление небезопасно.");
        }
        return world;
    }

    private static void loadChunks(World world, Cuboid region) {
        // Operations are synchronous: chunks cannot unload in the middle of this callback.
        // Do not remove another subsystem's plugin tickets (profiling/emulation may share a chunk).
        // Supported entity types can intersect the cuboid from an adjacent chunk.
        for (int x = (region.min().x() >> 4) - 1; x <= (region.max().x() >> 4) + 1; x++) {
            for (int z = (region.min().z() >> 4) - 1; z <= (region.max().z() >> 4) + 1; z++) world.getChunkAt(x, z);
        }
    }

    private static void eachBlock(World world, Cuboid region, Consumer<Block> action) {
        for (long x = region.min().x(); x <= region.max().x(); x++) {
            for (long y = region.min().y(); y <= region.max().y(); y++) {
                for (long z = region.min().z(); z <= region.max().z(); z++) {
                    action.accept(world.getBlockAt((int) x, (int) y, (int) z));
                }
            }
        }
    }
}
