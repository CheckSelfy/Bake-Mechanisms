package io.github.checkselfy.bakemechanisms.marking;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

class PaperOutputResolverTest {
    private final PaperOutputResolver resolver = new PaperOutputResolver();
    private final UUID worldId = UUID.randomUUID();

    @Test
    void barrelAndSingleChestResolveToOneBlock() {
        var barrel = block(Material.BARREL, 0);
        assertEquals(OutputContainer.Type.BARREL, resolver.resolve(barrel).type());
        var chestBlock = block(Material.CHEST, 1);
        var chest = mock(Chest.class);
        when(chestBlock.getState()).thenReturn(chest);
        when(chest.getInventory()).thenReturn(mock(Inventory.class));
        var result = resolver.resolve(chestBlock);
        assertEquals(OutputContainer.Type.CHEST, result.type());
        assertEquals(1, result.blocks().size());
    }

    @Test
    void eitherHalfResolvesToSameInventoryAcrossChunkBoundary() {
        var left = block(Material.CHEST, 15);
        var right = block(Material.CHEST, 16);
        var leftChest = mock(Chest.class);
        var rightChest = mock(Chest.class);
        var inventory = mock(DoubleChestInventory.class);
        var leftInventory = mock(Inventory.class);
        var rightInventory = mock(Inventory.class);
        when(left.getState()).thenReturn(leftChest);
        when(right.getState()).thenReturn(rightChest);
        when(leftChest.getBlock()).thenReturn(left);
        when(rightChest.getBlock()).thenReturn(right);
        when(leftChest.getInventory()).thenReturn(inventory);
        when(rightChest.getInventory()).thenReturn(inventory);
        when(inventory.getLeftSide()).thenReturn(leftInventory);
        when(inventory.getRightSide()).thenReturn(rightInventory);
        when(leftInventory.getHolder()).thenReturn(leftChest);
        when(rightInventory.getHolder()).thenReturn(rightChest);
        assertEquals(resolver.resolve(left), resolver.resolve(right));
        assertEquals(2, resolver.resolve(left).blocks().size());
        assertEquals(OutputContainer.Type.DOUBLE_CHEST, resolver.resolve(left).type());
    }

    @Test
    void unsupportedContainersAreRejected() {
        for (var type : new Material[] { Material.TRAPPED_CHEST, Material.HOPPER, Material.STONE,
                Material.ENDER_CHEST, Material.SHULKER_BOX }) {
            assertThrows(IllegalArgumentException.class, () -> resolver.resolve(block(type, 0)));
        }
    }

    @Test
    void incompleteDoubleChestIsRejected() {
        var block = block(Material.CHEST, 0);
        var chest = mock(Chest.class);
        var inventory = mock(DoubleChestInventory.class);
        var half = mock(Inventory.class);
        when(block.getState()).thenReturn(chest);
        when(chest.getInventory()).thenReturn(inventory);
        when(inventory.getLeftSide()).thenReturn(half);
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(block));
    }

    private Block block(Material material, int x) {
        var block = mock(Block.class);
        var world = mock(World.class);
        when(world.getUID()).thenReturn(worldId);
        when(block.getWorld()).thenReturn(world);
        when(block.getType()).thenReturn(material);
        when(block.getX()).thenReturn(x);
        return block;
    }
}
