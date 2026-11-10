package io.github.checkselfy.bakemechanisms.stasis;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.checkselfy.bakemechanisms.marking.BlockPosition;
import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.State;
import io.github.checkselfy.bakemechanisms.marking.OutputContainer;
import io.github.checkselfy.bakemechanisms.profiling.ProductionProfile;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class StasisServiceTest {
    private final UUID creator = UUID.randomUUID();
    private final UUID worldId = UUID.randomUUID();
    private final FarmRegistry registry = new FarmRegistry(100);
    private final StasisWorld world = mock(StasisWorld.class);
    private final SnapshotStore store = mock(SnapshotStore.class);
    private final StasisService service = new StasisService(registry, world, store);

    @Test
    void snapshotIsSavedBeforeFirstWorldMutationAndRestoreInvalidatesProfile() throws Exception {
        var farm = ready();
        when(world.capture(farm)).thenReturn(new byte[] { 1, 2 });
        service.enter(farm.id(), creator);
        var order = inOrder(world, store);
        order.verify(world).hasPlayerInside(farm.mechanism());
        order.verify(world).capture(farm);
        order.verify(store).save(any());
        order.verify(world).fill(farm.mechanism());
        assertEquals(State.STASIS, registry.requireCreator(farm.id(), creator).state());
        assertEquals(farm.profile(), registry.requireCreator(farm.id(), creator).profile());
        assertEquals(1, service.activeFarms().size());
        service.restore(farm.id(), creator);
        order.verify(world).hasPlayerInside(farm.mechanism());
        order.verify(world).restore(any(), aryEq(new byte[] { 1, 2 }));
        order.verify(store).deactivate(farm.id());
        assertEquals(State.NEEDS_PROFILING, registry.requireCreator(farm.id(), creator).state());
        assertNull(registry.requireCreator(farm.id(), creator).profile());
        assertTrue(service.activeFarms().isEmpty());
    }

    @Test
    void unsupportedContentAndSaveErrorsNeverReplaceAnyBlock() throws Exception {
        var farm = ready();
        when(world.capture(farm)).thenThrow(new IllegalStateException("unsupported"));
        assertThrows(IllegalArgumentException.class, () -> service.enter(farm.id(), creator));
        verifyNoInteractions(store);
        verify(world, never()).fill(any());
        doReturn(new byte[] { 1 }).when(world).capture(farm);
        doThrow(new IOException("disk full")).when(store).save(any());
        assertThrows(IllegalArgumentException.class, () -> service.enter(farm.id(), creator));
        verify(world, never()).fill(any());
        assertEquals(State.READY, registry.requireCreator(farm.id(), creator).state());
        assertTrue(service.activeFarms().isEmpty());
    }

    @Test
    void playerInsideAndNonCreatorAreRejectedBeforeCapture() {
        var farm = ready();
        assertThrows(IllegalArgumentException.class, () -> service.enter(farm.id(), UUID.randomUUID()));
        verifyNoInteractions(world, store);
        when(world.hasPlayerInside(farm.mechanism())).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> service.enter(farm.id(), creator));
        verify(world, never()).capture(any());
        assertEquals(State.READY, registry.requireCreator(farm.id(), creator).state());
    }

    @Test
    void movingPistonIsRetryableWithoutSavingOrReplacingAnyBlocks() {
        var farm = ready();
        when(world.capture(farm)).thenThrow(new PistonInMotionException());
        assertThrows(PistonInMotionException.class, () -> service.enter(farm.id(), creator));
        verifyNoInteractions(store);
        verify(world, never()).fill(any());
        assertEquals(State.READY, registry.requireCreator(farm.id(), creator).state());
    }

    @Test
    void onlyReadyCanEnterAndRepeatedEntryNeverOverwritesSnapshot() {
        var farm = ready();
        when(world.capture(farm)).thenReturn(new byte[] { 1 });
        service.enter(farm.id(), creator);
        assertThrows(IllegalArgumentException.class, () -> service.enter(farm.id(), creator));
        verify(world, times(1)).capture(any());
    }

    @Test
    void failedRestorationRefillsRegionKeepsSnapshotAndAllowsRetry() throws Exception {
        var farm = ready();
        when(world.capture(farm)).thenReturn(new byte[] { 1 });
        service.enter(farm.id(), creator);
        doThrow(new IllegalStateException("partial restore")).when(world).restore(any(), any());
        assertThrows(IllegalArgumentException.class, () -> service.restore(farm.id(), creator));
        verify(world, times(2)).fill(farm.mechanism());
        verify(store, never()).deactivate(any());
        assertEquals(State.STASIS, registry.requireCreator(farm.id(), creator).state());
        assertEquals(1, service.activeFarms().size());
        doNothing().when(world).restore(any(), any());
        service.restore(farm.id(), creator);
        assertEquals(State.NEEDS_PROFILING, registry.requireCreator(farm.id(), creator).state());
    }

    @Test
    void failedDeactivationAlsoRetainsProtectedStasis() throws Exception {
        var farm = ready();
        when(world.capture(farm)).thenReturn(new byte[] { 1 });
        service.enter(farm.id(), creator);
        doThrow(new IOException("read-only disk")).when(store).deactivate(farm.id());
        assertThrows(IllegalArgumentException.class, () -> service.restore(farm.id(), creator));
        assertEquals(State.STASIS, registry.requireCreator(farm.id(), creator).state());
        verify(world, times(2)).fill(farm.mechanism());
    }

    @Test
    void partialReplacementFailureRemainsRecoverableAndProtected() {
        var farm = ready();
        when(world.capture(farm)).thenReturn(new byte[] { 1 });
        doThrow(new IllegalStateException("world failure"), new IllegalStateException("rollback failure"))
                .when(world).fill(any());
        var error = assertThrows(IllegalArgumentException.class, () -> service.enter(farm.id(), creator));
        assertTrue(error.getMessage().contains("Снимок сохранён"));
        assertEquals(State.STASIS, registry.requireCreator(farm.id(), creator).state());
        assertEquals(1, service.activeFarms().size());
        assertEquals(1, error.getCause().getSuppressed().length);
    }

    @Test
    void recoveryReRegistersStasisWithoutRestoringOrLoadingWorld() throws Exception {
        var farm = ready();
        var stasisFarm = registry.markStasis(farm.id());
        var saved = new SavedSnapshot(stasisFarm, new byte[] { 1 });
        when(store.loadActive()).thenReturn(List.of(saved));
        var newRegistry = new FarmRegistry(1);
        var recovered = new StasisService(newRegistry, world, store);
        recovered.recover();
        assertEquals(stasisFarm, newRegistry.requireCreator(farm.id(), creator));
        verifyNoInteractions(world);
        assertThrows(IllegalArgumentException.class, () -> recovered.restore(farm.id(), UUID.randomUUID()));
        when(world.hasPlayerInside(farm.mechanism())).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> recovered.restore(farm.id(), creator));
        verify(world, never()).restore(any(), any());
    }

    @Test
    void recoveryOverStoredFarmsPersistsOnlyRealChanges() throws Exception {
        var farm = ready();
        var stasisFarm = registry.markStasis(farm.id());
        when(store.loadActive()).thenReturn(List.of(new SavedSnapshot(stasisFarm, new byte[] { 1 })));
        var changes = new java.util.ArrayList<FarmRegistry.MarkedFarm>();

        // Storage already holds the STASIS farm: nothing to persist.
        var upToDate = new FarmRegistry(1, changes::add);
        upToDate.restore(stasisFarm);
        new StasisService(upToDate, world, store).recover();
        assertTrue(changes.isEmpty());

        // Storage lags behind the manifest (crash before farm.yml was written): storage catches up.
        var lagging = new FarmRegistry(1, changes::add);
        lagging.restore(farm);
        new StasisService(lagging, world, store).recover();
        assertEquals(List.of(stasisFarm), changes);
        assertEquals(stasisFarm, lagging.requireCreator(farm.id(), creator));
    }

    @Test
    void storedStasisWithoutActiveSnapshotReturnsToNeedsProfiling() throws Exception {
        var farm = ready();
        var stasisFarm = registry.markStasis(farm.id());
        when(store.loadActive()).thenReturn(List.of());
        var loaded = new FarmRegistry(1);
        loaded.restore(stasisFarm);

        var reset = new StasisService(loaded, world, store).recover();

        assertEquals(List.of(farm.id()), reset);
        assertEquals(State.NEEDS_PROFILING, loaded.requireCreator(farm.id(), creator).state());
        verifyNoInteractions(world);
    }

    @Test
    void productionStartsOnlyAfterTheMechanismIsReplaced() {
        var production = mock(StasisProduction.class);
        var producing = new StasisService(registry, world, store, production);
        var farm = ready();
        when(world.capture(farm)).thenReturn(new byte[] { 1 });
        producing.enter(farm.id(), creator);
        var order = inOrder(world, production);
        order.verify(world).fill(farm.mechanism());
        order.verify(production).start(registry.requireCreator(farm.id(), creator));
    }

    @Test
    void partiallyReplacedMechanismIsRefilledAndStillProduces() {
        var production = mock(StasisProduction.class);
        var producing = new StasisService(registry, world, store, production);
        var farm = ready();
        when(world.capture(farm)).thenReturn(new byte[] { 1 });
        doThrow(new IllegalStateException("world failure")).doNothing().when(world).fill(any());
        assertThrows(IllegalArgumentException.class, () -> producing.enter(farm.id(), creator));
        verify(production).start(registry.requireCreator(farm.id(), creator));
    }

    @Test
    void failedRefillNeverRunsProductionAlongsideARemainingOriginal() {
        var production = mock(StasisProduction.class);
        var producing = new StasisService(registry, world, store, production);
        var farm = ready();
        when(world.capture(farm)).thenReturn(new byte[] { 1 });
        doThrow(new IllegalStateException("world failure"), new IllegalStateException("rollback failure"))
                .when(world).fill(any());
        assertThrows(IllegalArgumentException.class, () -> producing.enter(farm.id(), creator));
        verify(production, never()).start(any());
        assertEquals(State.STASIS, registry.requireCreator(farm.id(), creator).state());
    }

    @Test
    void restorePausesProductionAndLeavesOnlyTheLeftoverBufferBeforeTheProfileIsDropped() throws Exception {
        var production = mock(StasisProduction.class);
        var producing = new StasisService(registry, world, store, production);
        var farm = ready();
        when(world.capture(farm)).thenReturn(new byte[] { 1 });
        producing.enter(farm.id(), creator);
        producing.restore(farm.id(), creator);
        var order = inOrder(world, store, production);
        order.verify(production).pause(farm.id());
        order.verify(world).restore(any(), any());
        order.verify(store).deactivate(farm.id());
        order.verify(production).finish(farm.id());
        verify(production, never()).resume(any());
        assertEquals(State.NEEDS_PROFILING, registry.requireCreator(farm.id(), creator).state());
    }

    @Test
    void failedRestoreResumesProductionOverTheFiller() {
        var production = mock(StasisProduction.class);
        var producing = new StasisService(registry, world, store, production);
        var farm = ready();
        when(world.capture(farm)).thenReturn(new byte[] { 1 });
        producing.enter(farm.id(), creator);
        doThrow(new IllegalStateException("partial restore")).when(world).restore(any(), any());
        assertThrows(IllegalArgumentException.class, () -> producing.restore(farm.id(), creator));
        var order = inOrder(world, production);
        order.verify(production).pause(farm.id());
        order.verify(world).fill(farm.mechanism()); // the filler is back before production resumes
        order.verify(production).resume(farm.id());
        verify(production, never()).finish(any());
    }

    @Test
    void failedRestoreWithFailedRefillKeepsProductionPaused() {
        var production = mock(StasisProduction.class);
        var producing = new StasisService(registry, world, store, production);
        var farm = ready();
        when(world.capture(farm)).thenReturn(new byte[] { 1 });
        producing.enter(farm.id(), creator);
        doThrow(new IllegalStateException("partial restore")).when(world).restore(any(), any());
        doThrow(new IllegalStateException("rollback failure")).when(world).fill(any());
        assertThrows(IllegalArgumentException.class, () -> producing.restore(farm.id(), creator));
        verify(production).pause(farm.id());
        verify(production, never()).resume(any());
    }

    @Test
    void recoveryContinuesProductionOfEveryActiveSnapshot() throws Exception {
        var farm = ready();
        var stasisFarm = registry.markStasis(farm.id());
        when(store.loadActive()).thenReturn(List.of(new SavedSnapshot(stasisFarm, new byte[] { 1 })));
        var production = mock(StasisProduction.class);
        new StasisService(new FarmRegistry(1), world, store, production).recover();
        verify(production).start(stasisFarm);
        verifyNoInteractions(world);
    }

    private FarmRegistry.MarkedFarm ready() {
        var point = new BlockPosition(worldId, 0, 64, 0);
        var farm = registry.register(creator, new Cuboid(point, point), new OutputContainer(
                OutputContainer.Type.BARREL, Set.of(new BlockPosition(worldId, 32, 64, 0))));
        return registry.markReady(farm.id(), new ProductionProfile(Map.of(Material.COBBLESTONE, 1.0), 3600));
    }

    private static byte[] aryEq(byte[] data) { return org.mockito.AdditionalMatchers.aryEq(data); }
}
