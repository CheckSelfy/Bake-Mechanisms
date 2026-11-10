package io.github.checkselfy.bakemechanisms.profiling;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.checkselfy.bakemechanisms.marking.BlockPosition;
import io.github.checkselfy.bakemechanisms.marking.OutputContainer;
import io.github.checkselfy.bakemechanisms.marking.PaperOutputResolver;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PaperProfilingWorldTest {
    private final UUID worldId = UUID.randomUUID();
    private final OutputContainer barrelOutput =
            new OutputContainer(OutputContainer.Type.BARREL, Set.of(new BlockPosition(worldId, 3, 64, 7)));
    private final PaperOutputResolver resolver = mock(PaperOutputResolver.class);
    private final Block block = mock(Block.class);
    private final Inventory inventory = mock(Inventory.class);
    private PaperProfilingWorld paper;

    @BeforeEach
    void setup() {
        var plugin = mock(Plugin.class);
        var server = mock(Server.class);
        var world = mock(World.class);
        var barrel = mock(Barrel.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getWorld(worldId)).thenReturn(world);
        when(world.getBlockAt(3, 64, 7)).thenReturn(block);
        when(resolver.resolve(block)).thenReturn(barrelOutput);
        when(block.getState(false)).thenReturn(barrel);
        when(barrel.getInventory()).thenReturn(inventory);
        paper = new PaperProfilingWorld(plugin, resolver);
    }

    @Test
    void contentsAreNormalizedToItemTypes() {
        contents(new ItemStack[] {
                item(Material.COBBLESTONE, 64, 64), item(Material.COBBLESTONE, 10, 64), null,
                item(Material.AIR, 0, 64), item(Material.IRON_SWORD, 1, 1) });
        var snapshot = paper.readOutput(barrelOutput);
        assertEquals(Map.of(Material.COBBLESTONE, 74, Material.IRON_SWORD, 1), snapshot.counts());
        assertFalse(snapshot.full());
    }

    @Test
    void outputIsFullOnlyWhenEverySlotHoldsAFullStack() {
        contents(new ItemStack[] {
                item(Material.ENDER_PEARL, 16, 16), item(Material.IRON_SWORD, 1, 1) });
        assertTrue(paper.readOutput(barrelOutput).full());
        contents(new ItemStack[] {
                item(Material.ENDER_PEARL, 15, 16), item(Material.IRON_SWORD, 1, 1) });
        assertFalse(paper.readOutput(barrelOutput).full());
    }

    @Test
    void replacedOrBrokenOutputCannotBeRead() {
        when(resolver.resolve(block)).thenThrow(new IllegalArgumentException("Выходом может быть..."));
        assertThrows(IllegalStateException.class, () -> paper.readOutput(barrelOutput));
        doReturn(new OutputContainer(OutputContainer.Type.BARREL, Set.of(new BlockPosition(worldId, 9, 9, 9))))
                .when(resolver).resolve(block);
        assertThrows(IllegalStateException.class, () -> paper.readOutput(barrelOutput));
    }

    private void contents(ItemStack[] items) {
        when(inventory.getStorageContents()).thenReturn(items);
    }

    private static ItemStack item(Material type, int amount, int maxStack) {
        var item = mock(ItemStack.class);
        when(item.getType()).thenReturn(type);
        when(item.getAmount()).thenReturn(amount);
        when(item.getMaxStackSize()).thenReturn(maxStack);
        when(item.isEmpty()).thenReturn(type == Material.AIR || amount < 1);
        return item;
    }
}
