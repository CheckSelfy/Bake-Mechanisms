package io.github.checkselfy.bakemechanisms.emulation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.checkselfy.bakemechanisms.marking.BlockPosition;
import io.github.checkselfy.bakemechanisms.marking.OutputContainer;
import io.github.checkselfy.bakemechanisms.profiling.PaperProfilingWorld;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class PaperEmulationWorldTest {
    private final OutputContainer output = new OutputContainer(OutputContainer.Type.BARREL,
            Set.of(new BlockPosition(UUID.randomUUID(), 3, 64, 7)));
    private final PaperProfilingWorld paper = mock(PaperProfilingWorld.class);
    private final Inventory inventory = mock(Inventory.class);
    private final List<Integer> offered = new ArrayList<>();
    private MockedStatic<ItemStack> items;
    private int room;

    @BeforeEach
    void setup() {
        when(paper.inventory(output)).thenReturn(inventory);
        items = mockStatic(ItemStack.class);
        items.when(() -> ItemStack.of(any(Material.class))).thenAnswer(call -> stack(call.getArgument(0), 1));
        when(inventory.addItem(any(ItemStack[].class))).thenAnswer(call -> {
            ItemStack stack = call.getArgument(0);
            int amount = stack.getAmount();
            offered.add(amount);
            int accepted = Math.min(room, amount);
            room -= accepted;
            var leftovers = new HashMap<Integer, ItemStack>();
            if (accepted < amount) leftovers.put(0, stack(stack.getType(), amount - accepted));
            return leftovers;
        });
    }

    @AfterEach
    void close() {
        items.close();
    }

    @Test
    void insertsFreshPlainStacksSplitByMaxStackSize() {
        room = 1000;
        assertEquals(150, new PaperEmulationWorld(paper).insert(output, Material.COBBLESTONE, 150));
        assertEquals(List.of(64, 64, 22), offered);
        items.verify(() -> ItemStack.of(Material.COBBLESTONE));
    }

    @Test
    void reportsOnlyWhatFitsAndStopsOfferingOnceFull() {
        room = 70;
        assertEquals(70, new PaperEmulationWorld(paper).insert(output, Material.COBBLESTONE, 200));
        assertEquals(List.of(64, 64), offered);
        assertEquals(0, new PaperEmulationWorld(paper).insert(output, Material.COBBLESTONE, 5));
    }

    @Test
    void lostOutputIsReported() {
        when(paper.inventory(output)).thenThrow(new IllegalStateException("Выходной контейнер не найден."));
        assertThrows(IllegalStateException.class,
                () -> new PaperEmulationWorld(paper).insert(output, Material.COBBLESTONE, 1));
    }

    private static ItemStack stack(Material type, int amount) {
        var stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(type);
        when(stack.getAmount()).thenReturn(amount);
        when(stack.getMaxStackSize()).thenReturn(64);
        when(stack.asQuantity(anyInt())).thenAnswer(call -> stack(type, call.getArgument(0)));
        return stack;
    }
}
