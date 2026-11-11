package io.github.checkselfy.bakemechanisms.marking;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.checkselfy.bakemechanisms.profiling.ProductionProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FarmScreensTest {
    private final UUID creator = UUID.randomUUID();
    private final UUID worldId = UUID.randomUUID();
    private final FarmRegistry registry = new FarmRegistry(FarmRegistry.DEFAULT_MAX_VOLUME);
    private final Player player = mock(Player.class);
    private final List<FarmScreen> opened = new ArrayList<>();
    private int refreshed;
    private final List<List<String>> commands = new ArrayList<>();
    private FarmScreens screens;

    @BeforeEach
    void setup() {
        when(player.getUniqueId()).thenReturn(creator);
        var opener = new FarmScreens.Opener() {
            @Override public void open(Player viewer, FarmScreen screen) { opened.add(screen); }
            @Override public void refresh(Player viewer, FarmScreen screen) { refreshed++; }
        };
        screens = new FarmScreens(registry, (viewer, args) -> commands.add(List.of(args)), opener, Runnable::run);
    }

    @Test
    void everyStateIsItsOwnBlockWithAShortLabel() {
        farm(creator, "Needs", 0);
        registry.markProfiling(farm(creator, "Profiling", 1).id());
        ready(farm(creator, "Ready", 2));
        registry.markStasis(ready(farm(creator, "Stasis", 3)).id());

        screens.openList(player);

        var expected = Map.of("Needs", Map.entry(Material.YELLOW_CONCRETE, "Ждёт профилирования"),
                "Profiling", Map.entry(Material.OBSERVER, "Идёт профилирование"),
                "Ready", Map.entry(Material.EMERALD_BLOCK, "Готова к стазису"),
                "Stasis", Map.entry(Material.BEDROCK, "В стазисе"));
        expected.forEach((name, state) -> {
            var icon = icon(slotOf(name));
            assertEquals(state.getKey(), icon.material(), name);
            assertEquals(state.getValue(), icon.lore().getFirst(), name);
        });
    }

    @Test
    void availableActionsSitRightNextToTheStatusBlock() {
        var needs = farm(creator, "Needs", 0);
        registry.markProfiling(farm(creator, "Profiling", 1).id());
        ready(farm(creator, "Ready", 2));
        registry.markStasis(ready(farm(creator, "Stasis", 3)).id());
        screens.openList(player);

        assertActions("Needs", Material.CLOCK, Material.SPYGLASS, Material.BARRIER);
        assertActions("Profiling", null, null, null);
        assertActions("Ready", Material.BLUE_ICE, null, Material.BARRIER);
        assertActions("Stasis", Material.CAMPFIRE, null, null);
        assertEquals(1, opened.size(), "no separate screen per farm");

        click(slotOf("Needs") + 1);
        assertEquals(List.of(List.of("profile", "--id", needs.id().toString())), commands);
        verify(player).closeInventory();
    }

    @Test
    void longProfilingHasItsOwnButton() {
        var farm = farm(creator, "Cane", 0);
        screens.openList(player);
        click(slotOf("Cane") + 2);
        assertEquals(List.of(List.of("profile-long", "--id", farm.id().toString())), commands);
        verify(player).closeInventory();
    }

    @Test
    void profilingFarmOffersSprintOnlyToPlayersAllowedToUseTick() {
        var farm = farm(creator, "Iron", 0);
        registry.markProfiling(farm.id());
        screens.openList(player);
        assertNull(material(slotOf("Iron") + 1));

        when(player.hasPermission("minecraft.command.tick")).thenReturn(true);
        screens.openList(player);
        assertEquals(Material.SUGAR, material(slotOf("Iron") + 1));
        click(slotOf("Iron") + 1);
        assertEquals(List.of(List.of("sprint", "--id", farm.id().toString())), commands);
    }

    @Test
    void stasisAndUnstasisRunTheirCommands() {
        var ready = ready(farm(creator, "Ready", 0));
        var stasis = ready(farm(creator, "Stasis", 1));
        registry.markStasis(stasis.id());
        screens.openList(player);
        click(slotOf("Ready") + 1);
        screens.openList(player);
        click(slotOf("Stasis") + 1);
        assertEquals(List.of(List.of("stasis", "--id", ready.id().toString()),
                List.of("unstasis", "--id", stasis.id().toString())), commands);
    }

    @Test
    void deleteIsConfirmedInPlaceBySecondClick() {
        var farm = farm(creator, "Iron", 0);
        var id = farm.id().toString();
        screens.openList(player);
        int delete = slotOf("Iron") + 3;

        click(delete);
        assertEquals(List.of(List.of("unregister", "--id", id)), commands, "the request explains what is deleted");
        assertEquals(1, opened.size(), "confirmation stays on the same screen");
        assertEquals(1, refreshed);
        assertEquals(Material.TNT, icon(delete).material());

        click(delete);
        assertEquals(List.of("unregister", "--id", id, "confirm"), commands.getLast());
    }

    @Test
    void listShowsOnlyThePlayersOwnFarms() {
        farm(creator, "Mine", 0);
        farm(UUID.randomUUID(), "Theirs", 1);
        screens.openList(player);
        assertTrue(last().icons().values().stream().anyMatch(icon -> icon.name().equals("Mine")));
        assertTrue(last().icons().values().stream().noneMatch(icon -> icon.name().equals("Theirs")));
    }

    @Test
    void emptyListSaysHowToMarkAFarm() {
        screens.openList(player);
        assertEquals(1, last().icons().size());
        assertTrue(last().icons().values().iterator().next().lore().toString().contains("/bake wand"));
    }

    @Test
    void screenItemsCannotBeTakenMovedOrDraggedAndLeavingActsOnce() {
        var farm = ready(farm(creator, "Iron", 0));
        screens.openList(player);
        int stasis = slotOf("Iron") + 1;
        for (var type : List.of(ClickType.SHIFT_LEFT, ClickType.NUMBER_KEY, ClickType.DOUBLE_CLICK, ClickType.DROP)) {
            assertTrue(click(stasis, type, true).isCancelled(), type.name());
        }
        assertTrue(click(stasis, ClickType.LEFT, false).isCancelled());
        assertTrue(commands.isEmpty());
        click(stasis);
        click(stasis);
        assertEquals(List.of(List.of("stasis", "--id", farm.id().toString())), commands);

        var drag = mock(InventoryDragEvent.class);
        var view = view(last());
        when(drag.getView()).thenReturn(view);
        screens.onDrag(drag);
        verify(drag).setCancelled(true);
    }

    @Test
    void manyFarmsArePaged() {
        for (int i = 0; i < FarmScreens.FARMS_PER_PAGE + 1; i++) farm(creator, "F" + i, i);
        screens.openList(player);
        assertTrue(last().icons().values().stream().noneMatch(icon -> icon.name().equals("F" + FarmScreens.FARMS_PER_PAGE)));
        click(FarmScreens.NEXT_SLOT);
        assertTrue(last().icons().values().stream().anyMatch(icon -> icon.name().equals("F" + FarmScreens.FARMS_PER_PAGE)));
        click(FarmScreens.PREVIOUS_SLOT);
        assertTrue(last().icons().values().stream().anyMatch(icon -> icon.name().equals("F0")));
    }

    private void assertActions(String farm, Material main, Material longProfile, Material delete) {
        int status = slotOf(farm);
        assertEquals(main, material(status + 1), farm + " main action");
        assertEquals(longProfile, material(status + 2), farm + " long profiling");
        assertEquals(delete, material(status + 3), farm + " delete");
    }

    private Material material(int slot) {
        var icon = last().icons().get(slot);
        return icon == null ? null : icon.material();
    }

    private FarmRegistry.MarkedFarm farm(UUID owner, String name, int index) {
        int x = index * 32;
        var mechanism = new Cuboid(new BlockPosition(worldId, x, 0, 0), new BlockPosition(worldId, x + 2, 2, 2));
        var output = new OutputContainer(OutputContainer.Type.BARREL, Set.of(new BlockPosition(worldId, x + 3, 0, 0)));
        return registry.register(owner, name, mechanism, List.of(output));
    }

    private FarmRegistry.MarkedFarm ready(FarmRegistry.MarkedFarm farm) {
        registry.markProfiling(farm.id());
        return registry.markReady(farm.id(), new ProductionProfile(List.of(Map.of(Material.CACTUS, 1.0)), 3600));
    }

    private FarmScreen last() {
        return opened.getLast();
    }

    private FarmScreen.Icon icon(int slot) {
        return last().icons().get(slot);
    }

    private int slotOf(String name) {
        return last().icons().entrySet().stream().filter(entry -> entry.getValue().name().equals(name))
                .findFirst().orElseThrow().getKey();
    }

    private void click(int slot) {
        click(slot, ClickType.LEFT, true);
    }

    private InventoryView view(FarmScreen screen) {
        var view = mock(InventoryView.class);
        var top = mock(Inventory.class);
        when(top.getHolder(false)).thenReturn(screen);
        when(view.getTopInventory()).thenReturn(top);
        return view;
    }

    private InventoryClickEvent click(int slot, ClickType type, boolean inScreen) {
        var view = view(last());
        var bottom = mock(Inventory.class);
        when(view.getBottomInventory()).thenReturn(bottom);
        var event = spy(new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER,
                inScreen ? slot : FarmScreen.SIZE + slot, type, InventoryAction.NOTHING));
        doReturn(player).when(event).getWhoClicked();
        doReturn(inScreen ? view.getTopInventory() : bottom).when(event).getClickedInventory();
        screens.onClick(event);
        return event;
    }
}
