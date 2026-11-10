package io.github.checkselfy.bakemechanisms.stasis;

import static org.junit.jupiter.api.Assertions.*;

import io.github.checkselfy.bakemechanisms.marking.BlockPosition;
import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.MarkedFarm;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.State;
import io.github.checkselfy.bakemechanisms.marking.OutputContainer;
import io.github.checkselfy.bakemechanisms.profiling.ProductionProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSnapshotStoreTest {
    @TempDir Path directory;

    @Test
    void snapshotAndRecoveryMetadataSurviveNewStoreAndDeactivation() throws Exception {
        var snapshot = snapshot();
        var store = new FileSnapshotStore(directory, "26.3");
        store.save(snapshot);
        var restored = new FileSnapshotStore(directory, "26.3").loadActive().getFirst();
        assertEquals(snapshot.farm(), restored.farm());
        assertArrayEquals(snapshot.data(), restored.data());
        assertTrue(Files.readString(directory.resolve(snapshot.farm().id().toString()).resolve("stasis.yml"))
                .contains("COBBLESTONE"));
        store.deactivate(snapshot.farm().id());
        assertTrue(store.loadActive().isEmpty());
        assertTrue(Files.exists(directory.resolve(snapshot.farm().id().toString()).resolve("snapshot.bin")));
        store.save(snapshot);
        assertEquals(1, store.loadActive().size());
    }

    @Test
    void activeSnapshotCannotBeOverwrittenAndCorruptionIsDetected() throws Exception {
        var snapshot = snapshot();
        var store = new FileSnapshotStore(directory, "26.3");
        store.save(snapshot);
        assertThrows(IOException.class, () -> store.save(snapshot));
        Files.write(directory.resolve(snapshot.farm().id().toString()).resolve("snapshot.bin"), new byte[] { 0 });
        assertThrows(IOException.class, store::loadActive);
    }

    @Test
    void incompatibleVersionIsRejectedWithoutChangingWorldData() throws Exception {
        var snapshot = snapshot();
        new FileSnapshotStore(directory, "26.3").save(snapshot);
        assertThrows(IOException.class, () -> new FileSnapshotStore(directory, "26.4").loadActive());
        assertThrows(IOException.class, () -> new FileSnapshotStore(directory, "26.4").save(snapshot));
    }

    @Test
    void invalidManifestFailsExplicitlyAndUnpublishedBinaryIsIgnored() throws Exception {
        Files.createDirectories(directory.resolve("unused"));
        Files.write(directory.resolve("unused/blocks.nbt"), new byte[] { 1 });
        var store = new FileSnapshotStore(directory, "26.3");
        assertTrue(store.loadActive().isEmpty());
        Files.writeString(directory.resolve("unused/stasis.yml"), "format-version: 1\nminecraft-version: '26.3'\nid: invalid");
        assertThrows(IOException.class, store::loadActive);
    }

    @Test
    void savedDataCannotBeMutatedByCaller() {
        var snapshot = snapshot();
        var copy = snapshot.data();
        copy[0] = 9;
        assertEquals(1, snapshot.data()[0]);
    }

    private SavedSnapshot snapshot() {
        var world = UUID.randomUUID();
        var point = new BlockPosition(world, -1, 64, 0);
        var farm = new MarkedFarm(UUID.randomUUID(), UUID.randomUUID(), new Cuboid(point, point),
                new OutputContainer(OutputContainer.Type.DOUBLE_CHEST,
                        Set.of(new BlockPosition(world, 31, 64, 0), new BlockPosition(world, 32, 64, 0))),
                State.STASIS, new ProductionProfile(Map.of(Material.COBBLESTONE, 0.1), 3600));
        return new SavedSnapshot(farm, new byte[] { 1, 2, 3 });
    }
}
