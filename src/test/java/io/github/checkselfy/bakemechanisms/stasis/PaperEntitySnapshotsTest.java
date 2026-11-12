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
    @Test void allSpawnableLivingTypesExceptMultipartDragonHaveAnAuditedPolicy() {
        for (var type : EntityType.values()) {
            if (type.isAlive() && type.isSpawnable() && type != EntityType.ENDER_DRAGON) {
                assertTrue(PaperEntitySnapshots.supportedTypes().contains(type), "Unreviewed living type: " + type);
            }
        }
        assertFalse(PaperEntitySnapshots.supportedTypes().contains(EntityType.ENDER_DRAGON));
        assertFalse(PaperEntitySnapshots.supportedTypes().contains(EntityType.PLAYER));
        assertTrue(PaperEntitySnapshots.supportedTypes().contains(EntityType.ARROW));
        assertFalse(PaperEntitySnapshots.supportedTypes().contains(EntityType.SNOWBALL));
    }
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
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
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
        when(serialization.deserializeEntity(any(), eq(world), eq(true), eq(true))).thenReturn(root);
        var groups = snapshots.capture(world, region);
        assertEquals(1, groups.size());
        assertEquals(List.of(root.getUniqueId(), passenger.getUniqueId()), groups.getFirst().members());
        verify(serialization, times(1)).serializeEntity(root, EntitySerializationFlag.PASSENGERS);
        verify(root, never()).remove();
        verify(passenger, never()).remove();
    }

    @Test void unsupportedPassengerAndPlayerAreRejectedBeforeAnySerialization() {
        var root = entity(EntityType.MINECART);
        var unsupported = entity(EntityType.SNOWBALL);
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

    @Test void transientPoseCollisionRulesAndPickupPolicyAreRejectedBeforeSerialization() {
        var mob = mock(org.bukkit.entity.Zombie.class);
        initialize(mob, EntityType.ZOMBIE);
        when(world.getNearbyEntities(any(BoundingBox.class))).thenReturn(List.of(mob));
        when(mob.hasFixedPose()).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> snapshots.capture(world, region));
        when(mob.hasFixedPose()).thenReturn(false);
        when(mob.getCollidableExemptions()).thenReturn(java.util.Set.of(UUID.randomUUID()));
        assertThrows(IllegalStateException.class, () -> snapshots.capture(world, region));
        var item = mock(org.bukkit.entity.Item.class);
        initialize(item, EntityType.ITEM);
        when(item.canMobPickup()).thenReturn(false);
        when(world.getNearbyEntities(any(BoundingBox.class))).thenReturn(List.of(item));
        assertThrows(IllegalStateException.class, () -> snapshots.capture(world, region));
        verifyNoInteractions(serialization);
        verify(mob, never()).remove();
        verify(item, never()).remove();
    }

    @Test void activeTargetIsRecordedSeparatelyAndVerifiedOnDetachedCopy() {
        var mob = mock(org.bukkit.entity.Zombie.class);
        initialize(mob, EntityType.ZOMBIE);
        var target = mock(org.bukkit.entity.Cow.class);
        initialize(target, EntityType.COW);
        when(mob.getTarget()).thenReturn(target);
        var copy = mock(org.bukkit.entity.Zombie.class);
        initialize(copy, EntityType.ZOMBIE);
        var id = mob.getUniqueId();
        when(copy.getUniqueId()).thenReturn(id);
        var copiedTarget = new java.util.concurrent.atomic.AtomicReference<org.bukkit.entity.LivingEntity>();
        doAnswer(call -> { copiedTarget.set(call.getArgument(0)); return null; }).when(copy).setTarget(any());
        when(copy.getTarget()).thenAnswer(call -> copiedTarget.get());
        when(world.getNearbyEntities(any(BoundingBox.class))).thenReturn(List.of(mob));
        when(serialization.serializeEntity(mob, EntitySerializationFlag.PASSENGERS)).thenReturn(new byte[] { 1 });
        when(serialization.deserializeEntity(any(), eq(world), eq(true), eq(true))).thenReturn(copy);
        var group = snapshots.capture(world, region).getFirst();
        assertEquals(java.util.Map.of(id, target.getUniqueId()), group.targets());
        verify(copy).setTarget(target);
        verify(mob, never()).remove();
        verify(copy, never()).spawnAt(any(), any());
    }

    @Test void targetInAnotherGroupIsConnectedAfterBothGroupsSpawn() {
        var mob = mock(org.bukkit.entity.Zombie.class);
        initialize(mob, EntityType.ZOMBIE);
        var target = mock(org.bukkit.entity.Cow.class);
        initialize(target, EntityType.COW);
        var assigned = new java.util.concurrent.atomic.AtomicReference<org.bukkit.entity.LivingEntity>();
        doAnswer(call -> { assigned.set(call.getArgument(0)); return null; }).when(mob).setTarget(any());
        when(mob.getTarget()).thenAnswer(call -> assigned.get());
        when(mob.spawnAt(any(), any())).thenReturn(true);
        when(target.spawnAt(any(), any())).thenReturn(true);
        var groups = List.of(new SnapshotPayload.EntityGroup(mob.getUniqueId(), List.of(mob.getUniqueId()), new byte[] { 1 },
                java.util.Map.of(mob.getUniqueId(), target.getUniqueId())));
        snapshots.spawn(List.of(mob, target));
        snapshots.restoreTargets(List.of(mob, target), groups);
        var order = inOrder(mob, target);
        order.verify(mob).spawnAt(any(), any());
        order.verify(target).spawnAt(any(), any());
        order.verify(mob).setTarget(target);
        assertSame(target, mob.getTarget());
    }

    @Test void missingExternalTargetIsClearedWithoutSpawningOrRemovingAnything() {
        var mob = mock(org.bukkit.entity.Zombie.class);
        initialize(mob, EntityType.ZOMBIE);
        var id = mob.getUniqueId();
        var external = UUID.randomUUID();
        snapshots.restoreTargets(List.of(mob), List.of(new SnapshotPayload.EntityGroup(id, List.of(id), new byte[] { 1 },
                java.util.Map.of(id, external))));
        verify(mob).setTarget(null);
        verify(mob, never()).remove();
        verify(mob, never()).spawnAt(any(), any());
    }

    @Test void externalTargetThatChangedWorldIsCleared() {
        var mob = mock(org.bukkit.entity.Zombie.class);
        initialize(mob, EntityType.ZOMBIE);
        var target = mock(org.bukkit.entity.Cow.class);
        initialize(target, EntityType.COW);
        var targetId = target.getUniqueId();
        when(target.getWorld()).thenReturn(mock(World.class));
        when(server.getEntity(targetId)).thenReturn(target);
        snapshots.restoreTargets(List.of(mob), List.of(new SnapshotPayload.EntityGroup(mob.getUniqueId(),
                List.of(mob.getUniqueId()), new byte[] { 1 }, java.util.Map.of(mob.getUniqueId(), targetId))));
        verify(mob).setTarget(null);
        verify(target, never()).remove();
    }

    @Test void unsavedCartSettingsAreRejectedUsingDetachedNativeDefaults() {
        var cart = mock(org.bukkit.entity.Minecart.class);
        initialize(cart, EntityType.MINECART);
        var copy = mock(org.bukkit.entity.Minecart.class);
        initialize(copy, EntityType.MINECART);
        var cartId = cart.getUniqueId();
        when(copy.getUniqueId()).thenReturn(cartId);
        when(world.getNearbyEntities(any(BoundingBox.class))).thenReturn(List.of(cart));
        when(serialization.serializeEntity(cart, EntitySerializationFlag.PASSENGERS)).thenReturn(new byte[] { 1 });
        when(serialization.deserializeEntity(any(), eq(world), eq(true), eq(true))).thenReturn(copy);
        var defaults = new org.bukkit.util.Vector(0.95, 0.95, 0.95);
        when(cart.getFlyingVelocityMod()).thenReturn(defaults);
        when(copy.getFlyingVelocityMod()).thenReturn(defaults);
        when(cart.getDerailedVelocityMod()).thenReturn(defaults);
        when(copy.getDerailedVelocityMod()).thenReturn(defaults);
        when(cart.getMaxSpeed()).thenReturn(0.8);
        when(copy.getMaxSpeed()).thenReturn(0.4);
        assertThrows(IllegalStateException.class, () -> snapshots.capture(world, region));
        when(cart.getMaxSpeed()).thenReturn(0.4);
        when(cart.getDamage()).thenReturn(2.0);
        assertThrows(IllegalStateException.class, () -> snapshots.capture(world, region));
        when(cart.getDamage()).thenReturn(0.0);
        assertEquals(1, snapshots.capture(world, region).size());
        verify(cart, never()).remove();
        verify(copy, never()).spawnAt(any(), any());
    }

    @Test void leashedAndOpenInventoryEntitiesAreRejectedBeforeSerialization() {
        var cow = mock(org.bukkit.entity.Cow.class);
        initialize(cow, EntityType.COW);
        when(world.getNearbyEntities(any(BoundingBox.class))).thenReturn(List.of(cow));
        when(cow.isLeashed()).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> snapshots.capture(world, region));
        when(cow.isLeashed()).thenReturn(false);
        var cart = mock(org.bukkit.entity.minecart.StorageMinecart.class);
        initialize(cart, EntityType.CHEST_MINECART);
        var inventory = mock(org.bukkit.inventory.Inventory.class);
        when(cart.getInventory()).thenReturn(inventory);
        when(inventory.getViewers()).thenReturn(List.of(mock(Player.class)));
        when(world.getNearbyEntities(any(BoundingBox.class))).thenReturn(List.of(cart));
        assertThrows(IllegalStateException.class, () -> snapshots.capture(world, region));
        verifyNoInteractions(serialization);
    }

    @Test void nonPersistentSpawnerMobIsSerialized() {
        var cow = mock(org.bukkit.entity.Cow.class);
        initialize(cow, EntityType.COW);
        when(cow.isPersistent()).thenReturn(false);
        when(world.getNearbyEntities(any(BoundingBox.class))).thenReturn(List.of(cow));
        when(serialization.serializeEntity(cow, EntitySerializationFlag.PASSENGERS)).thenReturn(new byte[] { 1 });
        when(serialization.deserializeEntity(any(), eq(world), eq(true), eq(true))).thenReturn(cow);
        assertEquals(1, snapshots.capture(world, region).size());
    }

    @Test void justDiscardedEntityIsIgnoredBeforeSerialization() {
        var cow = mock(org.bukkit.entity.Cow.class);
        initialize(cow, EntityType.COW);
        when(cow.isValid()).thenReturn(false);
        when(world.getNearbyEntities(any(BoundingBox.class))).thenReturn(List.of(cow));
        assertTrue(snapshots.capture(world, region).isEmpty());
        verifyNoInteractions(serialization);
    }

    @Test void externalCreakingHeartAndActiveCopperGolemAreRejectedWithoutSerializing() {
        var creaking = mock(org.bukkit.entity.Creaking.class);
        initialize(creaking, EntityType.CREAKING);
        when(creaking.getHome()).thenReturn(new org.bukkit.Location(world, 10, 64, 0));
        when(world.getNearbyEntities(any(BoundingBox.class))).thenReturn(List.of(creaking));
        assertThrows(IllegalStateException.class, () -> snapshots.capture(world, region));
        var copper = mock(org.bukkit.entity.CopperGolem.class);
        initialize(copper, EntityType.COPPER_GOLEM);
        when(copper.getGolemState()).thenReturn(org.bukkit.entity.CopperGolem.State.GETTING_ITEM);
        when(world.getNearbyEntities(any(BoundingBox.class))).thenReturn(List.of(copper));
        assertThrows(IllegalStateException.class, () -> snapshots.capture(world, region));
        verifyNoInteractions(serialization);
    }

    @Test void inconsistentTamingIsRejectedBeforeTheOriginalIsRemoved() {
        var wolf = mock(org.bukkit.entity.Wolf.class);
        initialize(wolf, EntityType.WOLF);
        var restored = mock(org.bukkit.entity.Wolf.class);
        initialize(restored, EntityType.WOLF);
        var id = wolf.getUniqueId();
        when(restored.getUniqueId()).thenReturn(id);
        when(wolf.isTamed()).thenReturn(true);
        when(restored.isTamed()).thenReturn(false);
        when(world.getNearbyEntities(any(BoundingBox.class))).thenReturn(List.of(wolf));
        when(serialization.serializeEntity(wolf, EntitySerializationFlag.PASSENGERS)).thenReturn(new byte[] { 1 });
        when(serialization.deserializeEntity(any(), eq(world), eq(true), eq(true))).thenReturn(restored);
        assertThrows(IllegalStateException.class, () -> snapshots.capture(world, region));
        verify(wolf, never()).remove();
        verify(restored, never()).spawnAt(any(), any());
    }

    private Entity entity(EntityType type) {
        var entity = mock(Entity.class);
        initialize(entity, type);
        return entity;
    }

    private void initialize(Entity entity, EntityType type) {
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        when(entity.getType()).thenReturn(type);
        when(entity.isValid()).thenReturn(true);
        when(entity.isPersistent()).thenReturn(true);
        when(entity.getBoundingBox()).thenReturn(new BoundingBox(0, 64, 0, 0.8, 65, 0.8));
        when(entity.getLocation()).thenReturn(new org.bukkit.Location(world, 0, 64, 0));
        when(entity.getWorld()).thenReturn(world);
    }
}
