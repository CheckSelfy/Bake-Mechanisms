package io.github.checkselfy.bakemechanisms.profiling;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.checkselfy.bakemechanisms.marking.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Item;
import org.bukkit.inventory.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProfilingTransportProtectionTest {
    private final UUID id = UUID.randomUUID(), creator = UUID.randomUUID();
    private final World world = mock(World.class);
    private final FarmRegistry registry = new FarmRegistry(1000);
    private final ProfilingServiceTest.FakeWorld backend = new ProfilingServiceTest.FakeWorld();
    private final ProfilingService service = new ProfilingService(registry, backend, ProfilingSettings.DEFAULT);
    private final ProfilingTransportProtection guard = new ProfilingTransportProtection(service);
    private FarmRegistry.MarkedFarm farm;

    @BeforeEach void start() {
        when(world.getUID()).thenReturn(id);
        farm = registry.register(creator, new Cuboid(p(0,64,0), p(2,68,2)),
                new OutputContainer(OutputContainer.Type.BARREL, Set.of(p(3,64,1))));
        backend.contents.put(farm.outputs().getFirst(), new OutputSnapshot(Map.of(), false));
        service.start(farm.id(), creator);
    }

    @Test void outsideHopperAndReturningChainCannotFeedOutputButInternalHopperCan() {
        var attack = move(inventory(3,65,1), inventory(3,64,1));
        guard.onTransfer(attack); verify(attack).setCancelled(true);
        var returned = move(inventory(4,64,1), inventory(2,64,1));
        guard.onTransfer(returned); verify(returned).setCancelled(true);
        var production = move(inventory(2,64,1), inventory(3,64,1));
        guard.onTransfer(production); verify(production, never()).setCancelled(true);
        var drain = move(inventory(3,64,1), inventory(3,63,1));
        guard.onTransfer(drain); verify(drain, never()).setCancelled(true);
        service.cancel(farm.id(), creator);
        var after = move(inventory(3,65,1), inventory(3,64,1));
        guard.onTransfer(after); verify(after, never()).setCancelled(true);
        assertTrue(backend.tickets.isEmpty());
    }

    @Test void onlyNewFarmItemsMayBePickedUpAndMixedMergesAreCancelled() {
        var farmItem = item(1,65,1);
        var spawn = mock(ItemSpawnEvent.class); when(spawn.getEntity()).thenReturn(farmItem);
        guard.onItemSpawn(spawn);
        var pickup = pickup(farmItem); guard.onPickup(pickup); verify(pickup, never()).setCancelled(true);
        var oldItem = item(1,65,1);
        var oldPickup = pickup(oldItem); guard.onPickup(oldPickup); verify(oldPickup).setCancelled(true);
        var merge = mock(ItemMergeEvent.class);
        when(merge.getEntity()).thenReturn(oldItem); when(merge.getTarget()).thenReturn(farmItem);
        guard.onMerge(merge); verify(merge).setCancelled(true);
        var reverse = mock(ItemMergeEvent.class);
        when(reverse.getEntity()).thenReturn(farmItem); when(reverse.getTarget()).thenReturn(oldItem);
        guard.onMerge(reverse); verify(reverse).setCancelled(true);
        var fresh = item(1,65,1);
        var freshSpawn = mock(ItemSpawnEvent.class); when(freshSpawn.getEntity()).thenReturn(fresh);
        guard.onItemSpawn(freshSpawn);
        var safeMerge = mock(ItemMergeEvent.class);
        when(safeMerge.getEntity()).thenReturn(farmItem); when(safeMerge.getTarget()).thenReturn(fresh);
        guard.onMerge(safeMerge); verify(safeMerge, never()).setCancelled(true);
    }

    @Test void initialEquipmentInProductionAbortsWithoutLeavingProtectionOrTickets() {
        service.isolations().getFirst().initialize(new ProfilingIsolation.Sources(Set.of(), Set.of(Material.IRON_HELMET)));
        var helmet = item(1,65,1);
        when(helmet.getItemStack().getType()).thenReturn(Material.IRON_HELMET);
        var spawn = mock(ItemSpawnEvent.class); when(spawn.getEntity()).thenReturn(helmet);
        guard.onItemSpawn(spawn);
        assertEquals(FarmRegistry.State.NEEDS_PROFILING, registry.requireCreator(farm.id(), creator).state());
        assertTrue(service.isolations().isEmpty());
        assertTrue(backend.tickets.isEmpty());
        assertTrue(backend.messages.get(creator).getLast().contains("снаряжением"));
        verify(helmet, never()).remove();
    }

    @Test void playerDropBeforeSpawnCannotLaunderAnItemInsideTheMechanism() {
        var thrown = item(1,65,1);
        var drop = mock(PlayerDropItemEvent.class); when(drop.getItemDrop()).thenReturn(thrown);
        guard.onPlayerDrop(drop);
        var spawn = mock(ItemSpawnEvent.class); when(spawn.getEntity()).thenReturn(thrown);
        guard.onItemSpawn(spawn);
        var pickup = pickup(thrown); guard.onPickup(pickup); verify(pickup).setCancelled(true);
    }

    private Inventory inventory(int x,int y,int z) {
        var block = mock(Block.class); when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(x); when(block.getY()).thenReturn(y); when(block.getZ()).thenReturn(z);
        var holder = mock(Barrel.class); when(holder.getBlock()).thenReturn(block);
        var inventory = mock(Inventory.class); when(inventory.getHolder(false)).thenReturn(holder);
        return inventory;
    }
    private Item item(int x,int y,int z) {
        var item = mock(Item.class); when(item.getUniqueId()).thenReturn(UUID.randomUUID());
        when(item.getLocation()).thenReturn(new Location(world,x+.5,y,z+.5));
        var stack = mock(ItemStack.class); when(stack.getType()).thenReturn(Material.SUGAR_CANE);
        when(item.getItemStack()).thenReturn(stack); return item;
    }
    private InventoryMoveItemEvent move(Inventory from,Inventory to) {
        var event = mock(InventoryMoveItemEvent.class); when(event.getSource()).thenReturn(from); when(event.getDestination()).thenReturn(to);
        var item = mock(ItemStack.class); when(item.getType()).thenReturn(Material.SUGAR_CANE); when(event.getItem()).thenReturn(item); return event;
    }
    private InventoryPickupItemEvent pickup(Item item) {
        var destination = inventory(2,64,1);
        var event = mock(InventoryPickupItemEvent.class); when(event.getInventory()).thenReturn(destination); when(event.getItem()).thenReturn(item); return event;
    }
    private BlockPosition p(int x,int y,int z) { return new BlockPosition(id,x,y,z); }
}
