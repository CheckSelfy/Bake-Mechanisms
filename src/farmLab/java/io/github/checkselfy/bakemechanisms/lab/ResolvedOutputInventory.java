package io.github.checkselfy.bakemechanisms.lab;

import io.github.checkselfy.bakemechanisms.marking.PaperOutputResolver;
import java.util.EnumMap;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Reads a logical output through Paper's resolved inventory.  In particular, a
 * double chest is one 54-slot inventory; callers must not use command/NBT data
 * that happens to describe only one physical half.
 */
public final class ResolvedOutputInventory {
    private ResolvedOutputInventory() { }

    static Inventory at(Block output) {
        new PaperOutputResolver().resolve(output); // validates both halves before accounting
        if (!(output.getState() instanceof Container container)) {
            throw new IllegalArgumentException("Output is not a container");
        }
        return container.getInventory();
    }

    public static void clear(Block output) {
        at(output).clear();
    }

    public static Map<Material, Integer> count(Block output) {
        var result = new EnumMap<Material, Integer>(Material.class);
        for (ItemStack item : at(output).getContents()) {
            if (item != null && !item.getType().isAir()) result.merge(item.getType(), item.getAmount(), Integer::sum);
        }
        return Map.copyOf(result);
    }
}
