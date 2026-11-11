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
    private final List<List<String>> commands = new ArrayList<>();
    private FarmScreens screens;

    @BeforeEach
    void setup() {
        when(player.getUniqueId()).thenReturn(creator);
        screens = new FarmScreens(registry, (viewer, args) -> commands.add(List.of(args)),
                (viewer, screen) -> opened.add(screen), Runnable::run);
    }

    @Test
    void listShowsEveryStateWithItsOwnBlock() {
        var needs = farm(creator, "Needs", 0);
        var profiling = farm(creator, "Profiling", 1);
        registry.markProfiling(profiling.id());
        var ready = ready(farm(creator, "Ready", 2));
        var stasis = ready(farm(creator, "Stasis", 3));
        registry.markStasis(stasis.id());

        screens.openList(player);

        var blocks = Map.of("Needs", Material.YELLOW_CONCRETE, "Profiling", Material.OBSERVER,
                "Ready", Material.EMERALD_BLOCK, "Stasis", Material.BEDROCK);
        var icons = last().icons();
        assertEquals(4, icons.size());
        for (var icon : icons.values()) {
            var name = icon.name();
            assertEquals(blocks.get(name), icon.material(), name);
        }
        assertTrue(lore("Ready").stream().anyMatch(line -> line.contains("READY")), lore("Ready").toString());
        assertNotNull(needs);
        assertNotNull(ready);
    }

    @Test
    void listShowsOnlyThePlayersOwnFarms() {
        farm(creator, "Mine", 0);
        farm(UUID.randomUUID(), "Theirs", 1);
        screens.openList(player);
        assertEquals(List.of("Mine"), last().icons().values().stream().map(FarmScreen.Icon::name).toList());
    }

    @Test
    void emptyListSaysHowToMarkAFarm() {
        screens.openList(player);
        assertEquals(1, last().icons().size());
        assertTrue(last().icons().values().iterator().next().lore().toString().contains("/bake wand"));
    }

    @Test
    void farmScreenOffersOnlyTheActionsItsStateAllows() {
        var farm = ready(farm(creator, "Iron", 0));
        screens.openList(player);
        click(slotOf("Iron"));
        var screen = last();
        assertEquals(Material.EMERALD_BLOCK, screen.icons().get(FarmScreens.STATUS_SLOT).material());

        click(FarmScreens.PROFILE_SLOT);
        click(FarmScreens.UNSTASIS_SLOT);
        assertTrue(commands.isEmpty(), "unavailable actions do nothing");
        assertEquals(Material.GRAY_DYE, screen.icons().get(FarmScreens.PROFILE_SLOT).material());
        assertEquals(Material.GRAY_DYE, screen.icons().get(FarmScreens.UNSTASIS_SLOT).material());

        click(FarmScreens.STASIS_SLOT);
        assertEquals(List.of(List.of("stasis", "--id", farm.id().toString())), commands);
        verify(player).closeInventory();
    }

    @Test
    void eachStateEnablesItsActions() {
        var needs = farm(creator, "Needs", 0);
        var stasis = ready(farm(creator, "Stasis", 1));
        registry.markStasis(stasis.id());

        screens.openList(player);
        click(slotOf("Needs"));
        click(FarmScreens.PROFILE_SLOT);
        screens.openList(player);
        click(slotOf("Stasis"));
        click(FarmScreens.UNSTASIS_SLOT);

        assertEquals(List.of(List.of("profile", "--id", needs.id().toString()),
                List.of("unstasis", "--id", stasis.id().toString())), commands);
    }

    @Test
    void deleteAsksForConfirmationAndCanBeCancelled() {
        var farm = farm(creator, "Iron", 0);
        var id = farm.id().toString();
        screens.openList(player);
        click(slotOf("Iron"));
        click(FarmScreens.DELETE_SLOT);
        assertEquals(List.of(List.of("unregister", "--id", id)), commands, "the request shows what is deleted");
        assertEquals(FarmScreen.Kind.CONFIRM_DELETE, last().kind());

        click(FarmScreens.CANCEL_SLOT);
        assertEquals(FarmScreen.Kind.FARM, last().kind());
        assertEquals(1, commands.size());

        click(FarmScreens.DELETE_SLOT);
        click(FarmScreens.CONFIRM_SLOT);
        assertEquals(List.of("unregister", "--id", id, "confirm"), commands.getLast());
        assertEquals(FarmScreen.Kind.LIST, last().kind());
    }

    @Test
    void farmInStasisOrProfilingCannotBeDeletedFromTheScreen() {
        var farm = farm(creator, "Iron", 0);
        registry.markProfiling(farm.id());
        screens.openList(player);
        click(slotOf("Iron"));
        click(FarmScreens.DELETE_SLOT);
        assertTrue(commands.isEmpty());
        assertEquals(FarmScreen.Kind.FARM, last().kind());
    }

    @Test
    void backReturnsToTheList() {
        farm(creator, "Iron", 0);
        screens.openList(player);
        click(slotOf("Iron"));
        click(FarmScreens.BACK_SLOT);
        assertEquals(FarmScreen.Kind.LIST, last().kind());
    }

    @Test
    void screenItemsCannotBeTakenMovedOrDraggedAndActOnce() {
        var farm = ready(farm(creator, "Iron", 0));
        screens.openList(player);
        click(slotOf("Iron"));
        for (var type : List.of(ClickType.SHIFT_LEFT, ClickType.NUMBER_KEY, ClickType.DOUBLE_CLICK, ClickType.DROP)) {
            assertTrue(click(FarmScreens.STASIS_SLOT, type, true).isCancelled(), type.name());
        }
        assertTrue(click(FarmScreens.STASIS_SLOT, ClickType.LEFT, false).isCancelled());
        assertTrue(commands.isEmpty());
        var screen = last();
        click(FarmScreens.STASIS_SLOT);
        clickOn(screen, FarmScreens.STASIS_SLOT);
        assertEquals(List.of(List.of("stasis", "--id", farm.id().toString())), commands);

        var drag = mock(InventoryDragEvent.class);
        var view = view(screen);
        when(drag.getView()).thenReturn(view);
        screens.onDrag(drag);
        verify(drag).setCancelled(true);
    }

    @Test
    void manyFarmsArePaged() {
        for (int i = 0; i < FarmScreens.FARMS_PER_PAGE + 1; i++) farm(creator, "F" + i, i);
        screens.openList(player);
        assertEquals(FarmScreens.FARMS_PER_PAGE + 1, last().icons().size(), "a page of farms and the next arrow");
        click(FarmScreens.NEXT_SLOT);
        assertTrue(last().icons().values().stream().anyMatch(icon -> icon.name().equals("F" + FarmScreens.FARMS_PER_PAGE)));
        click(FarmScreens.PREVIOUS_SLOT);
        assertTrue(last().icons().values().stream().anyMatch(icon -> icon.name().equals("F0")));
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

    private List<String> lore(String name) {
        return last().icons().values().stream().filter(icon -> icon.name().equals(name)).findFirst().orElseThrow().lore();
    }

    private int slotOf(String name) {
        return last().icons().entrySet().stream().filter(entry -> entry.getValue().name().equals(name))
                .findFirst().orElseThrow().getKey();
    }

    private void click(int slot) {
        click(slot, ClickType.LEFT, true);
    }

    private void clickOn(FarmScreen screen, int slot) {
        click(screen, slot, ClickType.LEFT, true);
    }

    private InventoryClickEvent click(int slot, ClickType type, boolean inScreen) {
        return click(last(), slot, type, inScreen);
    }

    private InventoryView view(FarmScreen screen) {
        var view = mock(InventoryView.class);
        var top = mock(Inventory.class);
        when(top.getHolder(false)).thenReturn(screen);
        when(view.getTopInventory()).thenReturn(top);
        return view;
    }

    private InventoryClickEvent click(FarmScreen screen, int slot, ClickType type, boolean inScreen) {
        var view = view(screen);
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
