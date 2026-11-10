package io.github.checkselfy.bakemechanisms.stasis;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.checkselfy.bakemechanisms.marking.BlockPosition;
import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.MarkedFarm;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.State;
import io.github.checkselfy.bakemechanisms.marking.OutputContainer;
import io.github.checkselfy.bakemechanisms.profiling.ProductionProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StasisProtectionTest {
    private final UUID worldId = UUID.randomUUID();
    private final StasisService service = mock(StasisService.class);
    private final StasisProtection protection = new StasisProtection(service);

    @BeforeEach void setup() {
        var first = new BlockPosition(worldId, 0, 64, 0);
        var output = new BlockPosition(worldId, 32, 64, 0);
        when(service.activeFarms()).thenReturn(List.of(new MarkedFarm(UUID.randomUUID(), UUID.randomUUID(),
                new Cuboid(first, first), new OutputContainer(OutputContainer.Type.CHEST, Set.of(output)), State.STASIS,
                new ProductionProfile(Map.of(Material.COBBLESTONE, 1.0), 3600))));
    }

    @Test void blockBreakIsDeniedWithoutAnyGameModeBypass() {
        for (int x : new int[] { 0, 32 }) {
            var event = mock(BlockBreakEvent.class);
            doReturn(block(x)).when(event).getBlock();
            protection.onBreak(event);
            verify(event).setCancelled(true);
        }
        var outside = mock(BlockBreakEvent.class);
        doReturn(block(64)).when(outside).getBlock();
        protection.onBreak(outside);
        verify(outside, never()).setCancelled(true);
    }

    @Test void explosionRemovesOnlyProtectedBlocksFromDestructionList() {
        var inside = block(0);
        var output = block(32);
        var outside = block(64);
        var blocks = new ArrayList<>(List.of(inside, output, outside));
        var event = mock(EntityExplodeEvent.class);
        when(event.blockList()).thenReturn(blocks);
        protection.onEntityExplosion(event);
        assertEquals(List.of(outside), blocks);
    }

    @Test void pistonOutsideCannotMoveBlockIntoProtectedRegion() {
        var moving = block(-1);
        doReturn(block(0)).when(moving).getRelative(BlockFace.EAST);
        var event = mock(BlockPistonExtendEvent.class);
        doReturn(block(-2)).when(event).getBlock();
        when(event.getBlocks()).thenReturn(List.of(moving));
        when(event.getDirection()).thenReturn(BlockFace.EAST);
        protection.onPistonExtend(event);
        verify(event).setCancelled(true);
    }

    @Test void fluidAndEntityBlockChangesCannotOverwriteFiller() {
        var flow = mock(BlockFromToEvent.class);
        doReturn(block(-1)).when(flow).getBlock();
        doReturn(block(0)).when(flow).getToBlock();
        protection.onFluid(flow);
        verify(flow).setCancelled(true);
        var change = mock(EntityChangeBlockEvent.class);
        doReturn(block(0)).when(change).getBlock();
        protection.onEntityChange(change);
        verify(change).setCancelled(true);
    }

    @Test void placingChestCannotMergeOutputButHopperOutsideIsAllowed() {
        var chest = block(33);
        when(chest.getType()).thenReturn(Material.CHEST);
        var place = mock(BlockPlaceEvent.class);
        when(place.getBlockPlaced()).thenReturn(chest);
        protection.onPlace(place);
        verify(place).setCancelled(true);
        var hopper = block(33);
        when(hopper.getType()).thenReturn(Material.HOPPER);
        var allowed = mock(BlockPlaceEvent.class);
        when(allowed.getBlockPlaced()).thenReturn(hopper);
        protection.onPlace(allowed);
        verify(allowed, never()).setCancelled(true);
    }

    @Test void protectionEndsWhenSnapshotIsNoLongerActive() {
        when(service.activeFarms()).thenReturn(List.of());
        var event = mock(BlockBreakEvent.class);
        doReturn(block(0)).when(event).getBlock();
        protection.onBreak(event);
        verify(event, never()).setCancelled(true);
    }

    private Block block(int x) {
        var world = mock(World.class);
        when(world.getUID()).thenReturn(worldId);
        var block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(64);
        return block;
    }
}
