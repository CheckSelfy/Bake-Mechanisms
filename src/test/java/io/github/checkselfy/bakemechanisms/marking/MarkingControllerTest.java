package io.github.checkselfy.bakemechanisms.marking;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.checkselfy.bakemechanisms.profiling.ProfilingService;
import java.util.Set;
import java.util.UUID;
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
    private MarkingController controller;

    @BeforeEach
    void setup() {
        var plugin = mock(JavaPlugin.class);
        var server = mock(Server.class);
        when(plugin.getName()).thenReturn("BakeMechanisms");
        when(plugin.namespace()).thenReturn("bakemechanisms");
        when(plugin.getServer()).thenReturn(server);
        when(server.getWorld(worldId)).thenReturn(world);
        when(world.getUID()).thenReturn(worldId);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(player.getUniqueId()).thenReturn(creator);
        when(player.getWorld()).thenReturn(world);
        var meta = mock(ItemMeta.class);
        var pdc = mock(PersistentDataContainer.class);
        when(wand.getType()).thenReturn(Material.BLAZE_ROD);
        when(wand.hasItemMeta()).thenReturn(true);
        when(wand.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(new NamespacedKey("bakemechanisms", "marking_wand"), PersistentDataType.BYTE))
                .thenReturn((byte) 1);
        controller = new MarkingController(plugin, registry, outputs, profiling, mock(FarmRemoval.class));
    }

    @Test
    void wandAndCommandsRegisterFarmWithoutChangingWorld() {
        var corner = block(0, 0);
        var event = click(Action.LEFT_CLICK_BLOCK, corner);
        click(Action.RIGHT_CLICK_BLOCK, corner);
        selectOutput(32);
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
        click(Action.RIGHT_CLICK_BLOCK, block(0, 0));
        selectOutput(32);
        run("register");
        assertTrue(registry.findByCreator(creator).isEmpty());
    }

    @Test
    void ordinaryRodIsNotAWand() {
        when(wand.hasItemMeta()).thenReturn(false);
        var event = click(Action.LEFT_CLICK_BLOCK, block(0, 0));
        verify(event, never()).setUseInteractedBlock(any());
        selectOutput(32);
        run("register");
        assertTrue(registry.findByCreator(creator).isEmpty());
    }

    @Test
    void changedOrBrokenOutputIsRejectedAtRegistration() {
        selectCorners();
        var anchor = selectOutput(32);
        when(outputs.resolve(anchor)).thenThrow(new IllegalArgumentException("Контейнер сломан."));
        run("register");
        assertTrue(registry.findByCreator(creator).isEmpty());
        verify(player).sendMessage("Контейнер сломан.");
        doReturn(new OutputContainer(OutputContainer.Type.DOUBLE_CHEST,
                Set.of(new BlockPosition(worldId, 32, 0, 0), new BlockPosition(worldId, 33, 0, 0))))
                .when(outputs).resolve(anchor);
        run("register");
        assertTrue(registry.findByCreator(creator).isEmpty());
        verify(player).sendMessage(contains("изменился"));
    }

    @Test
    void quitAndWorldChangeClearUnfinishedSelection() {
        selectCorners();
        selectOutput(32);
        var quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(player);
        controller.onQuit(quit);
        run("register");
        assertTrue(registry.findByCreator(creator).isEmpty());
        selectCorners();
        selectOutput(32);
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
        click(Action.RIGHT_CLICK_BLOCK, block(0, 320));
        selectOutput(32);
        run("register");
        assertTrue(registry.findByCreator(creator).isEmpty());
        verify(player).sendMessage(contains("допустимую высоту"));
    }

    @Test
    void otherPlayerCannotInspectOrCompleteFarmId() {
        selectCorners();
        selectOutput(32);
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
    void clearDiscardsSelectionAndInvalidUuidHasReadableError() {
        selectCorners();
        selectOutput(32);
        run("clear");
        run("register");
        run("info", "--id", "invalid");
        assertTrue(registry.findByCreator(creator).isEmpty());
        verify(player).sendMessage("Укажите UUID фермы из /bake list.");
    }

    @Test
    void sneakingRightClickChoosesOutputWithoutReplacingSecondCorner() {
        var anchor = selectOutput(32);
        run("clear");
        selectCorners();
        when(player.isSneaking()).thenReturn(true);
        click(Action.RIGHT_CLICK_BLOCK, anchor);
        run("register");
        var farm = registry.findByCreator(creator).getFirst();
        assertEquals(new BlockPosition(worldId, 0, 0, 0), farm.mechanism().max());
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
        selectOutput(32);
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
        click(Action.RIGHT_CLICK_BLOCK, block(0, 0));
    }

    private Block selectOutput(int x) {
        var anchor = block(x, 0);
        var output = new OutputContainer(OutputContainer.Type.BARREL,
                Set.of(new BlockPosition(worldId, x, 0, 0)));
        when(world.getBlockAt(x, 0, 0)).thenReturn(anchor);
        when(outputs.resolve(anchor)).thenReturn(output);
        when(player.getTargetBlockExact(6)).thenReturn(anchor);
        run("output");
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
        var event = event(action, block);
        controller.onInteract(event);
        return event;
    }

    @Test
    void sprintIsRefusedAndHiddenWithoutTheRightToUseTick() {
        selectCorners();
        selectOutput(32);
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
        selectOutput(32);
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
        selectOutput(32);
        run("register", "Iron");
        var farm = registry.findByCreator(creator).getFirst();
        when(player.hasPermission("minecraft.command.tick")).thenReturn(true);
        when(profiling.remainingTicks(farm.id(), creator)).thenReturn(1200L);
        when(player.performCommand("tick sprint 1200")).thenReturn(false);

        run("sprint", "Iron");

        verify(player, never()).sendMessage(contains("Ускоряем"));
        verify(player).sendMessage(contains("не удалось"));
    }

    private void run(String... args) {
        controller.onCommand(player, command, "bake", args);
    }
}
