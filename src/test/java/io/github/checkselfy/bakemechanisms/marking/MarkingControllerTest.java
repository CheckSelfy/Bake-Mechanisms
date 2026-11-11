package io.github.checkselfy.bakemechanisms.marking;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.checkselfy.bakemechanisms.profiling.ProfilingService;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MarkingControllerTest {
    private final UUID creator = UUID.randomUUID();
    private final UUID worldId = UUID.randomUUID();
    private final FarmRegistry registry = new FarmRegistry(FarmRegistry.DEFAULT_MAX_VOLUME);
    private final Player player = mock(Player.class);
    private final World world = mock(World.class);
    private final PaperOutputResolver outputs = mock(PaperOutputResolver.class);
    private final Command command = mock(Command.class);
    private final ItemStack wand = mock(ItemStack.class);
    private final ProfilingService profiling = mock(ProfilingService.class);
    private final java.util.List<MarkingMenu> opened = new java.util.ArrayList<>();
    private int tick;
    private MarkingController controller;

    private final JavaPlugin plugin = mock(JavaPlugin.class);
    private final Server server = mock(Server.class);

    @BeforeEach
    void setup() {
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getName()).thenReturn("BakeMechanisms");
        when(plugin.namespace()).thenReturn("bakemechanisms");
        when(server.getCurrentTick()).thenAnswer(invocation -> tick);
        when(server.getWorld(worldId)).thenReturn(world);
        when(world.getUID()).thenReturn(worldId);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(player.getUniqueId()).thenReturn(creator);
        when(player.getWorld()).thenReturn(world);
        when(player.getInventory()).thenReturn(mock(PlayerInventory.class));
        var meta = mock(ItemMeta.class);
        var pdc = mock(PersistentDataContainer.class);
        when(wand.getType()).thenReturn(Material.BLAZE_ROD);
        when(wand.hasItemMeta()).thenReturn(true);
        when(wand.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(new NamespacedKey("bakemechanisms", "marking_wand"), PersistentDataType.BYTE))
                .thenReturn((byte) 1);
        controller = new MarkingController(plugin, registry, outputs, profiling, mock(FarmRemoval.class),
                (viewer, menu, selection) -> opened.add(menu), Runnable::run);
    }

    @Test
    void wandAndCommandsRegisterFarmWithoutChangingWorld() {
        var corner = block(0, 0);
        var event = click(Action.LEFT_CLICK_BLOCK, corner);
        click(Action.LEFT_CLICK_BLOCK, corner);
        selectOutput(1);
        run("register");
        var farm = registry.findByCreator(creator).getFirst();
        assertEquals(FarmRegistry.State.NEEDS_PROFILING, farm.state());
        verify(event).setUseInteractedBlock(Event.Result.DENY);
        verify(event).setUseItemInHand(Event.Result.DENY);
        verify(corner, never()).setType(any());
        run("info", "--id", farm.id().toString());
        verify(player, atLeastOnce()).sendMessage(contains(farm.id().toString()));
        run("register");
        assertEquals(1, registry.findByCreator(creator).size());
        verify(player).sendMessage(contains("Выберите оба угла"));
    }

    @Test
    void offHandDoesNotSelectCorners() {
        var event = event(Action.LEFT_CLICK_BLOCK, block(0, 0));
        when(event.getHand()).thenReturn(EquipmentSlot.OFF_HAND);
        controller.onInteract(event);
        click(Action.LEFT_CLICK_BLOCK, block(0, 0));
        selectOutput(1);
        run("register");
        assertTrue(registry.findByCreator(creator).isEmpty());
    }

    @Test
    void ordinaryRodIsNotAWand() {
        when(wand.hasItemMeta()).thenReturn(false);
        var event = click(Action.LEFT_CLICK_BLOCK, block(0, 0));
        verify(event, never()).setUseInteractedBlock(any());
        selectOutput(1);
        run("register");
        assertTrue(registry.findByCreator(creator).isEmpty());
    }

    @Test
    void changedOrBrokenOutputIsRejectedAtRegistration() {
        selectCorners();
        var anchor = selectOutput(1);
        when(outputs.resolve(anchor)).thenThrow(new IllegalArgumentException("Контейнер сломан."));
        run("register");
        assertTrue(registry.findByCreator(creator).isEmpty());
        verify(player).sendMessage("Контейнер сломан.");
        doReturn(new OutputContainer(OutputContainer.Type.DOUBLE_CHEST,
                Set.of(new BlockPosition(worldId, 1, 0, 0), new BlockPosition(worldId, 1, 0, 1))))
                .when(outputs).resolve(anchor);
        run("register");
        assertTrue(registry.findByCreator(creator).isEmpty());
        verify(player).sendMessage(contains("изменился"));
    }

    @Test
    void quitAndWorldChangeClearUnfinishedSelection() {
        selectCorners();
        selectOutput(1);
        var quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(player);
        controller.onQuit(quit);
        run("register");
        assertTrue(registry.findByCreator(creator).isEmpty());
        selectCorners();
        selectOutput(1);
        var change = mock(PlayerChangedWorldEvent.class);
        when(change.getPlayer()).thenReturn(player);
        controller.onWorldChange(change);
        run("register");
        assertTrue(registry.findByCreator(creator).isEmpty());
    }

    @Test
    void invalidHeightAndMissingSelectionAreRejected() {
        run("register");
        click(Action.LEFT_CLICK_BLOCK, block(0, 320));
        click(Action.LEFT_CLICK_BLOCK, block(0, 320));
        selectOutput(1);
        run("register");
        assertTrue(registry.findByCreator(creator).isEmpty());
        verify(player).sendMessage(contains("допустимую высоту"));
    }

    @Test
    void otherPlayerCannotInspectOrCompleteFarmId() {
        selectCorners();
        selectOutput(1);
        run("register");
        var farm = registry.findByCreator(creator).getFirst();
        var other = mock(Player.class);
        when(other.getUniqueId()).thenReturn(UUID.randomUUID());
        controller.onCommand(other, command, "bake", new String[] { "info", "--id", farm.id().toString() });
        verify(other).sendMessage(contains("только её создатель"));
        assertTrue(controller.onTabComplete(other, command, "bake", new String[] { "info", "--id", "" }).isEmpty());
        assertEquals(java.util.List.of(farm.id().toString()),
                controller.onTabComplete(player, command, "bake", new String[] { "info", "--id", "" }));
    }

    @Test
    void unselectDiscardsSelectionAndInvalidUuidHasReadableError() {
        selectCorners();
        selectOutput(1);
        run("unselect");
        run("register");
        run("info", "--id", "invalid");
        assertTrue(registry.findByCreator(creator).isEmpty());
        verify(player).sendMessage("Укажите UUID фермы из /bake list.");
    }

    @Test
    void leftClicksSelectCornersThenOutputsAndTheWandMovesToWhatIsMissing() {
        var anchor = output(1);
        click(Action.LEFT_CLICK_BLOCK, block(0, 5));
        click(Action.LEFT_CLICK_BLOCK, block(0, 0));
        click(Action.LEFT_CLICK_BLOCK, anchor);
        run("register");
        var farm = registry.findByCreator(creator).getFirst();
        assertEquals(new BlockPosition(worldId, 0, 0, 0), farm.mechanism().min());
        assertEquals(new BlockPosition(worldId, 0, 5, 0), farm.mechanism().max());
        assertEquals(OutputContainer.Type.BARREL, farm.outputs().get(0).type());
    }

    @Test
    void wandPreventsBlockBreakIncludingCreativeEvent() {
        var inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(wand);
        var event = mock(BlockBreakEvent.class);
        when(event.getPlayer()).thenReturn(player);
        controller.onBreak(event);
        verify(event).setCancelled(true);
    }

    @Test
    void selectionIsNotSharedWithOtherPlayers() {
        selectCorners();
        selectOutput(1);
        var other = mock(Player.class);
        var otherId = UUID.randomUUID();
        when(other.getUniqueId()).thenReturn(otherId);
        controller.onCommand(other, command, "bake", new String[] { "register" });
        assertTrue(registry.findByCreator(otherId).isEmpty());
        verify(other).sendMessage(contains("Выберите оба угла"));
        run("register");
        assertEquals(1, registry.findByCreator(creator).size());
    }

    private void selectCorners() {
        click(Action.LEFT_CLICK_BLOCK, block(0, 0));
        click(Action.LEFT_CLICK_BLOCK, block(0, 0));
    }

    private Block selectOutput(int x) {
        var anchor = output(x);
        when(player.getTargetBlockExact(6)).thenReturn(anchor);
        run("select-output");
        return anchor;
    }

    /** A barrel at (x, 0, 0) that the resolver accepts as an output container. */
    private Block output(int x) {
        var anchor = block(x, 0);
        var output = new OutputContainer(OutputContainer.Type.BARREL,
                Set.of(new BlockPosition(worldId, x, 0, 0)));
        when(world.getBlockAt(x, 0, 0)).thenReturn(anchor);
        when(outputs.resolve(anchor)).thenReturn(output);
        return anchor;
    }

    private Block block(int x, int y) {
        var block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(y);
        return block;
    }

    private PlayerInteractEvent event(Action action, Block block) {
        var event = mock(PlayerInteractEvent.class);
        when(event.getItem()).thenReturn(wand);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getClickedBlock()).thenReturn(block);
        when(event.getAction()).thenReturn(action);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }

    private PlayerInteractEvent click(Action action, Block block) {
        tick += 20;
        var event = event(action, block);
        controller.onInteract(event);
        return event;
    }

    @Test
    void sprintIsRefusedAndHiddenWithoutTheRightToUseTick() {
        selectCorners();
        selectOutput(1);
        run("register", "Iron");
        when(player.hasPermission("minecraft.command.tick")).thenReturn(false);

        run("sprint", "Iron");

        verify(player).sendMessage(contains("/tick"));
        verify(player, never()).sendMessage(contains("Ускоряем"));
        verify(player, never()).performCommand(anyString());
        verifyNoInteractions(profiling);
        assertFalse(controller.onTabComplete(player, command, "bake", new String[] { "" }).contains("sprint"));
        assertEquals(java.util.List.of(),
                controller.onTabComplete(player, command, "bake", new String[] { "sprint", "" }));
    }

    @Test
    void sprintRunsTickSprintForTheRemainingTicksAsThePlayer() {
        selectCorners();
        selectOutput(1);
        run("register", "Iron");
        var farm = registry.findByCreator(creator).getFirst();
        when(player.hasPermission("minecraft.command.tick")).thenReturn(true);
        when(profiling.remainingTicks(farm.id(), creator)).thenReturn(1200L);
        when(player.performCommand("tick sprint 1200")).thenReturn(true);

        run("sprint", "Iron");

        verify(player).performCommand("tick sprint 1200");
        verify(player).sendMessage(contains("1200"));
        assertTrue(controller.onTabComplete(player, command, "bake", new String[] { "sp" }).contains("sprint"));
        assertEquals(java.util.List.of("Iron", "--id"),
                controller.onTabComplete(player, command, "bake", new String[] { "sprint", "" }));
    }

    @Test
    void sprintReportsAFailureWhenTheServerDoesNotRunTick() {
        selectCorners();
        selectOutput(1);
        run("register", "Iron");
        var farm = registry.findByCreator(creator).getFirst();
        when(player.hasPermission("minecraft.command.tick")).thenReturn(true);
        when(profiling.remainingTicks(farm.id(), creator)).thenReturn(1200L);
        when(player.performCommand("tick sprint 1200")).thenReturn(false);

        run("sprint", "Iron");

        verify(player, never()).sendMessage(contains("Ускоряем"));
        verify(player).sendMessage(contains("не удалось"));
    }

    @Test
    void profileCommandsChooseShortOrLongRunAndOfferFarmCompletion() {
        selectCorners(); selectOutput(1); run("register", "Cane");
        var farm = registry.findByCreator(creator).getFirst();
        run("profile", "Cane");
        verify(profiling).start(farm.id(),creator);
        run("PROFILE-LONG", "--id", farm.id().toString());
        verify(profiling).start(farm.id(),creator,36000);
        assertTrue(controller.onTabComplete(player,command,"bake",new String[]{"profile"}).contains("profile-long"));
        assertEquals(java.util.List.of("Cane","--id"),controller.onTabComplete(player,command,"bake",new String[]{"profile-long",""}));
    }

    @Test
    void renamedCommandsPointToTheirNewNamesWithoutActing() {
        selectCorners();
        selectOutput(1);
        run("register", "Iron");
        var renames = java.util.Map.of("output", "select-output", "clear", "unselect",
                "cancel", "cancel-profiling", "restore", "unstasis", "delete", "unregister");
        for (var rename : renames.entrySet()) {
            run(rename.getKey(), "Iron");
            verify(player).sendMessage("Команда /bake " + rename.getKey() + " переименована: используйте /bake "
                    + rename.getValue() + ".");
        }
        verifyNoInteractions(profiling);
        assertEquals(1, registry.findByCreator(creator).size());
    }

    @Test
    void completionOffersOnlyTheNewNames() {
        when(player.hasPermission("minecraft.command.tick")).thenReturn(true);
        var all = controller.onTabComplete(player, command, "bake", new String[] { "" });
        assertTrue(all.containsAll(java.util.List.of("select-output", "unselect", "cancel-profiling", "unregister")));
        for (var old : java.util.List.of("output", "clear", "cancel", "delete")) assertFalse(all.contains(old), old);
        assertEquals(java.util.List.of("unselect", "unregister"),
                controller.onTabComplete(player, command, "bake", new String[] { "un" }));
        selectCorners();
        selectOutput(1);
        run("register", "Iron");
        assertEquals(java.util.List.of("Iron", "--id"),
                controller.onTabComplete(player, command, "bake", new String[] { "cancel-profiling", "" }));
        assertEquals(java.util.List.of("confirm"),
                controller.onTabComplete(player, command, "bake", new String[] { "unregister", "Iron", "c" }));
    }

    @Test
    void cancelProfilingCancelsTheNamedFarm() {
        selectCorners();
        selectOutput(1);
        run("register", "Iron");
        var farm = registry.findByCreator(creator).getFirst();
        run("cancel-profiling", "Iron");
        verify(profiling).cancel(farm.id(), creator);
    }

    @Test
    void rightClickWithWandOpensMenuWithoutSelectingAnything() {
        var event = click(Action.RIGHT_CLICK_BLOCK, block(0, 0));
        assertEquals(1, opened.size());
        verify(event).setUseInteractedBlock(Event.Result.DENY);
        click(Action.RIGHT_CLICK_AIR, null);
        assertEquals(2, opened.size());
        selectOutput(1);
        run("register");
        assertTrue(registry.findByCreator(creator).isEmpty());
    }

    @Test
    void menuButtonsChooseTheModeOfTheNextLeftClick() {
        click(Action.LEFT_CLICK_BLOCK, block(0, 0));
        click(Action.LEFT_CLICK_BLOCK, block(0, 5));
        openMenu();
        var press = press(MarkingMenu.Button.FIRST_CORNER);
        assertTrue(press.isCancelled());
        verify(player).closeInventory();
        click(Action.LEFT_CLICK_BLOCK, block(0, 1));
        click(Action.LEFT_CLICK_BLOCK, output(1));
        run("register");
        var farm = registry.findByCreator(creator).getFirst();
        assertEquals(new BlockPosition(worldId, 0, 1, 0), farm.mechanism().min());
    }

    @Test
    void menuWalksTheWholeMarkingWithoutTypingCommands() {
        openMenu();
        press(MarkingMenu.Button.SECOND_CORNER);
        click(Action.LEFT_CLICK_BLOCK, block(0, 5));
        openMenu();
        press(MarkingMenu.Button.FIRST_CORNER);
        click(Action.LEFT_CLICK_BLOCK, block(0, 0));
        openMenu();
        press(MarkingMenu.Button.OUTPUT);
        click(Action.LEFT_CLICK_BLOCK, output(1));
        openMenu();
        press(MarkingMenu.Button.REGISTER);
        assertEquals(1, registry.findByCreator(creator).size());
        verify(player).sendMessage(contains("Ферма зарегистрирована"));
    }

    @Test
    void reopeningTheMenuKeepsTheSelectionAndUnselectClearsIt() {
        click(Action.LEFT_CLICK_BLOCK, block(0, 0));
        click(Action.LEFT_CLICK_BLOCK, block(0, 5));
        openMenu();
        openMenu();
        click(Action.LEFT_CLICK_BLOCK, output(1));
        openMenu();
        when(player.isOnline()).thenReturn(true);
        var open = view();
        when(player.getOpenInventory()).thenReturn(open);
        press(MarkingMenu.Button.UNSELECT);
        assertEquals(4, opened.size(), "unselect shows the menu again with the empty selection");
        press(MarkingMenu.Button.REGISTER);
        assertTrue(registry.findByCreator(creator).isEmpty());
        verify(player).sendMessage(contains("Текущее выделение очищено"));
    }

    @Test
    void menuItemsCannotBeTakenMovedOrDuplicated() {
        selectCorners();
        selectOutput(1);
        openMenu();
        for (var type : java.util.List.of(ClickType.SHIFT_LEFT, ClickType.NUMBER_KEY, ClickType.DOUBLE_CLICK,
                ClickType.MIDDLE, ClickType.DROP, ClickType.SWAP_OFFHAND)) {
            assertTrue(menuClick(MarkingMenu.Button.REGISTER.slot(), type, true).isCancelled(), type.name());
        }
        assertTrue(menuClick(MarkingMenu.Button.REGISTER.slot(), ClickType.LEFT, false).isCancelled(),
                "clicks in the player's own inventory are cancelled too, so shift-click cannot move items in");
        assertTrue(registry.findByCreator(creator).isEmpty(), "only a plain click on the menu presses a button");
        var drag = mock(InventoryDragEvent.class);
        var view = view();
        when(drag.getView()).thenReturn(view);
        controller.onMenuDrag(drag);
        verify(drag).setCancelled(true);
    }

    @Test
    void oneButtonActsOnceEvenIfClickedAgainBeforeTheMenuCloses() {
        selectCorners();
        selectOutput(1);
        openMenu();
        press(MarkingMenu.Button.REGISTER);
        press(MarkingMenu.Button.REGISTER);
        assertEquals(1, registry.findByCreator(creator).size());
        verify(player, never()).sendMessage(contains("Выберите оба угла"));
    }

    @Test
    void unselectDoesNotReopenAMenuThePlayerAlreadyClosed() {
        openMenu();
        when(player.isOnline()).thenReturn(true);
        var other = mock(InventoryView.class);
        when(other.getTopInventory()).thenReturn(mock(Inventory.class));
        when(player.getOpenInventory()).thenReturn(other);
        press(MarkingMenu.Button.UNSELECT);
        assertEquals(1, opened.size());
    }

    @Test
    void offHandCannotUseItsItemWhileTheWandIsInTheMainHand() {
        holdWand();
        var event = mock(PlayerInteractEvent.class);
        when(event.getHand()).thenReturn(EquipmentSlot.OFF_HAND);
        when(event.getItem()).thenReturn(mock(ItemStack.class));
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(event.getPlayer()).thenReturn(player);
        controller.onInteract(event);
        verify(event).setUseInteractedBlock(Event.Result.DENY);
        verify(event).setUseItemInHand(Event.Result.DENY);
        assertTrue(opened.isEmpty());
    }

    @Test
    void doubleRightClickEventsOpenTheMenuOnce() {
        tick += 20;
        controller.onInteract(event(Action.RIGHT_CLICK_BLOCK, block(0, 0)));
        controller.onInteract(event(Action.RIGHT_CLICK_AIR, null));
        assertEquals(1, opened.size());
    }

    @Test
    void holdingLeftClickOnAnOutputDoesNotToggleItBackOut() {
        selectCorners();
        var anchor = output(1);
        tick += 20;
        controller.onInteract(event(Action.LEFT_CLICK_BLOCK, anchor));
        tick += 1;
        controller.onInteract(event(Action.LEFT_CLICK_BLOCK, anchor));
        run("register");
        assertEquals(1, registry.findByCreator(creator).size());
    }

    @Test
    void ordinaryInventoriesAreNotTouched() {
        var event = mock(InventoryClickEvent.class);
        var view = mock(InventoryView.class);
        var top = mock(Inventory.class);
        when(view.getTopInventory()).thenReturn(top);
        when(event.getView()).thenReturn(view);
        controller.onMenuClick(event);
        verify(event, never()).setCancelled(anyBoolean());
    }

    @Test
    void actionBarShowsTheStepJustDoneAndWhatComesNext() {
        click(Action.LEFT_CLICK_BLOCK, block(0, 0));
        assertEquals("✔ Угол 1: 0, 0, 0 → ЛКМ: второй угол", lastActionBar());
        openMenu();
        press(MarkingMenu.Button.REGISTER);
        assertTrue(lastActionBar().startsWith("✖"), lastActionBar());
    }

    @Test
    void heldWandKeepsShowingTheModeCoordinatesAndWhatIsMissing() {
        holdWand();
        click(Action.LEFT_CLICK_BLOCK, block(0, 0));
        tick += MarkingController.NOTICE_TICKS;
        controller.refreshActionBars();
        assertEquals("ЛКМ: второй угол | углов 1/2, контейнеров 0", lastActionBar());
        click(Action.LEFT_CLICK_BLOCK, block(0, 5));
        click(Action.LEFT_CLICK_BLOCK, output(1));
        tick += MarkingController.NOTICE_TICKS;
        controller.refreshActionBars();
        assertEquals("Всё выбрано | ПКМ → «Зарегистрировать ферму»", lastActionBar());
        openMenu();
        press(MarkingMenu.Button.REGISTER);
        assertTrue(lastActionBar().contains("Ферма зарегистрирована"), lastActionBar());
    }

    @Test
    void noActionBarForPlayersWithoutTheWand() {
        var inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        doReturn(java.util.List.of(player)).when(server).getOnlinePlayers();
        controller.refreshActionBars();
        verify(player, never()).sendActionBar(any(Component.class));
    }

    @Test
    void everyActionBarLineFitsOnScreenEvenWithExtremeCoordinatesAndLongErrors() {
        holdWand();
        openMenu();
        press(MarkingMenu.Button.REGISTER);
        assertTrue(lastActionBar().contains("чат"), "a long error points to chat: " + lastActionBar());
        var far = block(-29_999_999, 319);
        when(far.getZ()).thenReturn(-29_999_999);
        click(Action.LEFT_CLICK_BLOCK, far);
        tick += MarkingController.NOTICE_TICKS;
        controller.refreshActionBars();
        click(Action.LEFT_CLICK_BLOCK, block(-29_999_998, 318));
        openMenu();
        press(MarkingMenu.Button.OUTPUT);
        click(Action.LEFT_CLICK_BLOCK, output(1));
        tick += MarkingController.NOTICE_TICKS;
        controller.refreshActionBars();
        var captor = org.mockito.ArgumentCaptor.forClass(Component.class);
        verify(player, atLeastOnce()).sendActionBar(captor.capture());
        for (var line : captor.getAllValues()) {
            var text = PlainTextComponentSerializer.plainText().serialize(line);
            assertTrue(text.length() <= MarkingController.ACTION_BAR_LIMIT, text.length() + ": " + text);
        }
    }

    private void holdWand() {
        var inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(wand);
        doReturn(java.util.List.of(player)).when(server).getOnlinePlayers();
    }

    private String lastActionBar() {
        var captor = org.mockito.ArgumentCaptor.forClass(Component.class);
        verify(player, atLeastOnce()).sendActionBar(captor.capture());
        return PlainTextComponentSerializer.plainText().serialize(captor.getValue());
    }

    private void openMenu() {
        click(Action.RIGHT_CLICK_AIR, null);
    }

    private InventoryClickEvent press(MarkingMenu.Button button) {
        return menuClick(button.slot(), ClickType.LEFT, true);
    }

    private InventoryView view() {
        var view = mock(InventoryView.class);
        var top = mock(Inventory.class);
        when(top.getHolder(false)).thenReturn(opened.getLast());
        when(view.getTopInventory()).thenReturn(top);
        return view;
    }

    /** A real cancellable click on the last opened menu, or on the player's inventory below it. */
    private InventoryClickEvent menuClick(int slot, ClickType type, boolean inMenu) {
        tick += 1;
        var view = view();
        var bottom = mock(Inventory.class);
        when(view.getBottomInventory()).thenReturn(bottom);
        var event = spy(new InventoryClickEvent(view, inMenu ? InventoryType.SlotType.CONTAINER
                : InventoryType.SlotType.QUICKBAR, inMenu ? slot : 27 + slot, type, InventoryAction.NOTHING));
        doReturn(player).when(event).getWhoClicked();
        doReturn(inMenu ? view.getTopInventory() : bottom).when(event).getClickedInventory();
        controller.onMenuClick(event);
        return event;
    }

    private void run(String... args) {
        controller.onCommand(player, command, "bake", args);
    }
}
