package io.github.checkselfy.bakemechanisms.profiling;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.checkselfy.bakemechanisms.marking.BlockPosition;
import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry;
import io.github.checkselfy.bakemechanisms.marking.OutputContainer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.util.BoundingBox;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProfilingProtectionTest {
    private final UUID worldId = UUID.randomUUID();
    private final UUID creator = UUID.randomUUID();
    private final World world = mock(World.class);
    private final Player player = mock(Player.class);
    private final FarmRegistry registry = new FarmRegistry(FarmRegistry.DEFAULT_MAX_VOLUME);
    private final ProfilingServiceTest.FakeWorld fake = new ProfilingServiceTest.FakeWorld();
    private final ProfilingService service = new ProfilingService(registry, fake, ProfilingSettings.DEFAULT);
    private final ProfilingProtection protection = new ProfilingProtection(service);
    private FarmRegistry.MarkedFarm farm;

    @BeforeEach
    void setup() {
        when(world.getUID()).thenReturn(worldId);
        when(player.getWidth()).thenReturn(0.6);
        when(player.getHeight()).thenReturn(1.8);
        // Mechanism occupies x/y/z 0..9, output barrel at (20, 0, 0).
        farm = registry.register(creator, new Cuboid(position(0, 0, 0), position(9, 9, 9)),
                new OutputContainer(OutputContainer.Type.BARREL, Set.of(position(20, 0, 0))));
        fake.contents.put(farm.output(), new OutputSnapshot(Map.of(), false));
        service.start(farm.id(), creator);
    }

    @Test
    void mechanismBlocksCannotBeChangedOrUsedWhileProfiling() {
        var inside = block(5, 5, 5);
        var outside = block(15, 0, 0);
        var breakInside = new BlockBreakEvent(inside, player);
        protection.onBreak(breakInside);
        assertTrue(breakInside.isCancelled());
        var breakOutput = new BlockBreakEvent(block(20, 0, 0), player);
        protection.onBreak(breakOutput);
        assertTrue(breakOutput.isCancelled());
        var breakOutside = new BlockBreakEvent(outside, player);
        protection.onBreak(breakOutside);
        assertFalse(breakOutside.isCancelled());

        var place = mock(BlockPlaceEvent.class);
        when(place.getBlockPlaced()).thenReturn(inside);
        protection.onPlace(place);
        verify(place).setCancelled(true);

        var lever = mock(PlayerInteractEvent.class);
        when(lever.getClickedBlock()).thenReturn(inside);
        when(lever.getPlayer()).thenReturn(player);
        protection.onInteract(lever);
        verify(lever).setCancelled(true);

        service.cancel(farm.id(), creator);
        var afterCancel = new BlockBreakEvent(inside, player);
        protection.onBreak(afterCancel);
        assertFalse(afterCancel.isCancelled());
    }

    @Test
    void playersCannotWalkOrTeleportIntoMechanism() {
        var walkIn = move(new Location(world, -1, 0, 5), new Location(world, -0.2, 0, 5));
        protection.onMove(walkIn);
        verify(walkIn).setCancelled(true);

        var alongside = move(new Location(world, -1, 0, 5), new Location(world, -0.4, 0, 5));
        protection.onMove(alongside);
        verify(alongside, never()).setCancelled(true);

        var onTop = move(new Location(world, 5, 10, 5), new Location(world, 6, 10, 5));
        protection.onMove(onTop);
        verify(onTop, never()).setCancelled(true);

        var teleport = mock(PlayerTeleportEvent.class);
        when(teleport.getPlayer()).thenReturn(player);
        when(teleport.getFrom()).thenReturn(new Location(world, 100, 0, 100));
        when(teleport.getTo()).thenReturn(new Location(world, 5, 5, 5));
        protection.onTeleport(teleport);
        verify(teleport).setCancelled(true);
    }

    @Test
    void outputInventoryCannotBeOpenedOrExploded() {
        var outputBlock = block(20, 0, 0);
        var barrel = mock(Barrel.class);
        when(barrel.getBlock()).thenReturn(outputBlock);
        var open = mock(InventoryOpenEvent.class);
        var inventory = mock(Inventory.class);
        when(open.getInventory()).thenReturn(inventory);
        when(inventory.getHolder(false)).thenReturn(barrel);
        when(open.getPlayer()).thenReturn(player);
        protection.onOpen(open);
        verify(open).setCancelled(true);

        var unrelatedDouble = mock(DoubleChest.class);
        var left = mock(Chest.class);
        var right = mock(Chest.class);
        var leftBlock = block(40, 0, 0);
        var rightBlock = block(41, 0, 0);
        when(left.getBlock()).thenReturn(leftBlock);
        when(right.getBlock()).thenReturn(rightBlock);
        when(unrelatedDouble.getLeftSide(false)).thenReturn(left);
        when(unrelatedDouble.getRightSide(false)).thenReturn(right);
        var other = mock(InventoryOpenEvent.class);
        var otherInventory = mock(Inventory.class);
        when(other.getInventory()).thenReturn(otherInventory);
        when(otherInventory.getHolder(false)).thenReturn(unrelatedDouble);
        protection.onOpen(other);
        verify(other, never()).setCancelled(true);

        var blocks = new ArrayList<>(List.of(outputBlock, block(5, 5, 5), block(30, 0, 0)));
        var explosion = mock(EntityExplodeEvent.class);
        when(explosion.blockList()).thenReturn(blocks);
        protection.onEntityExplode(explosion);
        assertEquals(2, blocks.size());
        assertFalse(blocks.contains(outputBlock), "output survives; mechanism explosions are the farm's business");
    }

    @Test
    void playersCannotChangeEntitiesOrFluidsInsideMechanism() {
        var villager = mock(Entity.class);
        when(villager.getBoundingBox()).thenReturn(new BoundingBox(4, 0, 4, 5, 2, 5));
        when(villager.getWorld()).thenReturn(world);
        var interact = mock(PlayerInteractEntityEvent.class);
        when(interact.getRightClicked()).thenReturn(villager);
        when(interact.getPlayer()).thenReturn(player);
        protection.onInteractEntity(interact);
        verify(interact).setCancelled(true);

        var arrow = mock(Arrow.class);
        when(arrow.getShooter()).thenReturn(player);
        var shot = mock(EntityDamageByEntityEvent.class);
        when(shot.getEntity()).thenReturn(villager);
        when(shot.getDamager()).thenReturn(arrow);
        protection.onDamage(shot);
        verify(shot).setCancelled(true);

        var zombie = mock(Entity.class);
        var mobHit = mock(EntityDamageByEntityEvent.class);
        when(mobHit.getEntity()).thenReturn(villager);
        when(mobHit.getDamager()).thenReturn(zombie);
        protection.onDamage(mobHit);
        verify(mobHit, never()).setCancelled(true);

        var frame = mock(Hanging.class);
        when(frame.getBoundingBox()).thenReturn(new BoundingBox(9.9, 1, 1, 10, 2, 2));
        when(frame.getWorld()).thenReturn(world);
        var removal = mock(HangingBreakByEntityEvent.class);
        when(removal.getEntity()).thenReturn(frame);
        when(removal.getRemover()).thenReturn(player);
        protection.onHangingBreak(removal);
        verify(removal).setCancelled(true);

        var bucket = mock(PlayerBucketEmptyEvent.class);
        var water = block(9, 9, 9);
        when(bucket.getBlock()).thenReturn(water);
        when(bucket.getPlayer()).thenReturn(player);
        protection.onBucket(bucket);
        verify(bucket).setCancelled(true);
    }

    @Test
    void blocksCannotBePlacedAgainstOutputAndMinecartsInsideCannotBeBroken() {
        var hopperBelow = mock(BlockPlaceEvent.class);
        var below = block(20, -1, 0);
        when(hopperBelow.getBlockPlaced()).thenReturn(below);
        protection.onPlace(hopperBelow);
        verify(hopperBelow).setCancelled(true);

        var diagonal = mock(BlockPlaceEvent.class);
        var far = block(21, -1, 0);
        when(diagonal.getBlockPlaced()).thenReturn(far);
        protection.onPlace(diagonal);
        verify(diagonal, never()).setCancelled(true);

        var cart = mock(Vehicle.class);
        when(cart.getBoundingBox()).thenReturn(new BoundingBox(1, 1, 1, 2, 2, 2));
        when(cart.getWorld()).thenReturn(world);
        var hit = mock(VehicleDamageEvent.class);
        when(hit.getVehicle()).thenReturn(cart);
        when(hit.getAttacker()).thenReturn(player);
        protection.onVehicleDamage(hit);
        verify(hit).setCancelled(true);
        var destroy = mock(VehicleDestroyEvent.class);
        when(destroy.getVehicle()).thenReturn(cart);
        when(destroy.getAttacker()).thenReturn(player);
        protection.onVehicleDestroy(destroy);
        verify(destroy).setCancelled(true);
    }

    private PlayerMoveEvent move(Location from, Location to) {
        var event = mock(PlayerMoveEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getFrom()).thenReturn(from);
        when(event.getTo()).thenReturn(to);
        return event;
    }

    private Block block(int x, int y, int z) {
        var block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(y);
        when(block.getZ()).thenReturn(z);
        return block;
    }

    private BlockPosition position(int x, int y, int z) {
        return new BlockPosition(worldId, x, y, z);
    }
}
