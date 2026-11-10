package io.github.checkselfy.bakemechanisms.emulation;

import io.github.checkselfy.bakemechanisms.marking.OutputContainer;
import io.github.checkselfy.bakemechanisms.profiling.PaperProfilingWorld;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Paper adapter for {@link EmulationWorld}; shares output resolution with profiling. */
public final class PaperEmulationWorld implements EmulationWorld {
    private final PaperProfilingWorld paper;

    public PaperEmulationWorld(PaperProfilingWorld paper) {
        this.paper = Objects.requireNonNull(paper, "paper");
    }

    /** Fresh stacks carry only the item type: normalization drops enchantments, damage and names. */
    @Override
    public int insert(OutputContainer output, Material type, int amount) {
        var inventory = paper.inventory(output);
        var template = ItemStack.of(type);
        int maxStack = template.getMaxStackSize();
        int accepted = 0;
        while (accepted < amount) {
            int offered = Math.min(maxStack, amount - accepted);
            int leftover = inventory.addItem(template.asQuantity(offered)).values().stream()
                    .mapToInt(ItemStack::getAmount).sum();
            accepted += offered - leftover;
            if (leftover > 0) break;
        }
        return accepted;
    }

    @Override
    public void notify(UUID playerId, String message) {
        paper.notify(playerId, message);
    }
}
