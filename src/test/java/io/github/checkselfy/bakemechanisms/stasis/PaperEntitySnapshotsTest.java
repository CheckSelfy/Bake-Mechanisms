package io.github.checkselfy.bakemechanisms.stasis;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.checkselfy.bakemechanisms.marking.BlockPosition;
import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.papermc.paper.entity.EntitySerializationFlag;
import java.util.List;
import java.util.UUID;
import org.bukkit.Server;
import org.bukkit.UnsafeValues;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("deprecation")
class PaperEntitySnapshotsTest {
    private final World world = mock(World.class);
    private final Server server = mock(Server.class);
    private final UnsafeValues serialization = mock(UnsafeValues.class);
    private final UUID worldId = UUID.randomUUID();
    private final BlockPosition point = new BlockPosition(worldId, 0, 64, 0);
    private final Cuboid region = new Cuboid(point, point);
    private PaperEntitySnapshots snapshots;

    @BeforeEach void setup() {
        var plugin = mock(Plugin.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getUnsafe()).thenReturn(serialization);
        snapshots = new PaperEntitySnapshots(plugin);
    }

    @Test void boundingBoxIntersectionIncludesOutsideCentreAndSerializesRootOnlyOnce() {
        var root = entity(EntityType.MINECART);
        var passenger = entity(EntityType.ZOMBIE);
        // Root's centre is irrelevant: only the overlapping bounds select the whole group.
        when(root.getBoundingBox()).thenReturn(new BoundingBox(-0.6, 64, 0, 0.2, 65, 0.8));
        when(root.getPassengers()).thenReturn(List.of(passenger));
        when(passenger.getVehicle()).thenReturn(root);
        when(world.getNearbyEntities(any(BoundingBox.class))).thenReturn(List.of(passenger, root));
        when(serialization.serializeEntity(root, EntitySerializationFlag.PASSENGERS)).thenReturn(new byte[] { 1 });
        var groups = snapshots.capture(world, region);
        assertEquals(1, groups.size());
        assertEquals(List.of(root.getUniqueId(), passenger.getUniqueId()), groups.getFirst().members());
        verify(serialization, times(1)).serializeEntity(root, EntitySerializationFlag.PASSENGERS);
        verify(root, never()).remove();
        verify(passenger, never()).remove();
    }

    @Test void unsupportedPassengerAndPlayerAreRejectedBeforeAnySerialization() {
        var root = entity(EntityType.MINECART);
        var unsupported = entity(EntityType.CREEPER);
        when(root.getPassengers()).thenReturn(List.of(unsupported));
        when(world.getNearbyEntities(any(BoundingBox.class))).thenReturn(List.of(root));
        assertThrows(IllegalStateException.class, () -> snapshots.capture(world, region));
        var player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(root.getPassengers()).thenReturn(List.of(player));
        assertThrows(IllegalStateException.class, () -> snapshots.capture(world, region));
        verifyNoInteractions(serialization);
        verify(root, never()).remove();
    }

    @Test void preparedGroupMustHaveExactlyTheRecordedIds() {
        var root = entity(EntityType.COW);
        // Construct a valid group whose serialized root disagrees with its recovery metadata.
        var expected = UUID.randomUUID();
        var group = new SnapshotPayload.EntityGroup(expected, List.of(expected), new byte[] { 1 });
        when(serialization.deserializeEntity(any(), eq(world), eq(true), eq(true))).thenReturn(root);
        var corrupt = group;
        assertThrows(IllegalStateException.class, () -> snapshots.prepare(world, List.of(corrupt)));
        verify(root, never()).spawnAt(any(), any());
    }

    @Test void removalUsesSavedIdsIncludingPassengersOutsideTheCuboid() {
        var root = entity(EntityType.MINECART);
        var passenger = entity(EntityType.ZOMBIE);
        when(server.getEntity(root.getUniqueId())).thenReturn(root);
        when(server.getEntity(passenger.getUniqueId())).thenReturn(passenger);
        var group = new SnapshotPayload.EntityGroup(root.getUniqueId(), List.of(root.getUniqueId(), passenger.getUniqueId()), new byte[] { 1 });
        snapshots.remove(List.of(group));
        verify(root).remove();
        verify(passenger).remove();
    }

    @Test void cyclicVehicleLinksAreRejected() {
        var entity = entity(EntityType.MINECART);
        when(entity.getVehicle()).thenReturn(entity);
        when(world.getNearbyEntities(any(BoundingBox.class))).thenReturn(List.of(entity));
        assertThrows(IllegalStateException.class, () -> snapshots.capture(world, region));
        verifyNoInteractions(serialization);
    }

    @Test void minecartInventoryIsClearedBeforeDiscardToPreventDroppedCopies() {
        var cart = mock(org.bukkit.entity.minecart.StorageMinecart.class);
        var inventory = mock(org.bukkit.inventory.Inventory.class);
        var id = UUID.randomUUID();
        when(cart.getInventory()).thenReturn(inventory);
        when(server.getEntity(id)).thenReturn(cart);
        snapshots.remove(List.of(new SnapshotPayload.EntityGroup(id, List.of(id), new byte[] { 1 })));
        var order = inOrder(inventory, cart);
        order.verify(inventory).clear();
        order.verify(cart).remove();
    }

    private Entity entity(EntityType type) {
        var entity = mock(Entity.class);
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        when(entity.getType()).thenReturn(type);
        when(entity.isValid()).thenReturn(true);
        when(entity.isPersistent()).thenReturn(true);
        when(entity.getBoundingBox()).thenReturn(new BoundingBox(0, 64, 0, 0.8, 65, 0.8));
        return entity;
    }
}
