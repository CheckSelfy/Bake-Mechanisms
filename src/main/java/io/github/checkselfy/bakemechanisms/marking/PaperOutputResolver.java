package io.github.checkselfy.bakemechanisms.marking;

import java.util.Set;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.inventory.DoubleChestInventory;

/** Resolves one real inventory, including both physical halves of a double chest. */
public final class PaperOutputResolver {
    public OutputContainer resolve(Block block) {
        if (block.getType() == Material.BARREL) {
            return new OutputContainer(OutputContainer.Type.BARREL, Set.of(position(block)));
        }
        if (block.getType() != Material.CHEST || !(block.getState() instanceof Chest chest)) {
            throw new IllegalArgumentException("Выходом может быть обычный/двойной сундук или бочка.");
        }
        if (chest.getInventory() instanceof DoubleChestInventory inventory) {
            if (!(inventory.getLeftSide().getHolder() instanceof Chest left)
                    || !(inventory.getRightSide().getHolder() instanceof Chest right)
                    || left.getBlock().getType() != Material.CHEST
                    || right.getBlock().getType() != Material.CHEST) {
                throw new IllegalArgumentException("Не удалось определить обе половины двойного сундука.");
            }
            return new OutputContainer(OutputContainer.Type.DOUBLE_CHEST,
                    Set.of(position(left.getBlock()), position(right.getBlock())));
        }
        return new OutputContainer(OutputContainer.Type.CHEST, Set.of(position(block)));
    }

    public static BlockPosition position(Block block) {
        return new BlockPosition(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }
}
