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

/** Paper adapter for {@link ProfilingWorld}. */
public final class PaperProfilingWorld implements ProfilingWorld {
    private final Plugin plugin;
    private final PaperOutputResolver resolver;

    public PaperProfilingWorld(Plugin plugin, PaperOutputResolver resolver) {
        this.plugin = plugin;
        this.resolver = resolver;
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

    private Inventory inventory(OutputContainer output) {
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
