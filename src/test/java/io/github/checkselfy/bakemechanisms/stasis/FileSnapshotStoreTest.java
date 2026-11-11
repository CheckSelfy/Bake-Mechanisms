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
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
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
    void everyOutputAndItsRatesSurviveInOrderWhileTheNameStaysWithTheRegistration() throws Exception {
        var world = UUID.randomUUID();
        var point = new BlockPosition(world, -1, 64, 0);
        var farm = new MarkedFarm(UUID.randomUUID(), UUID.randomUUID(), "Iron", new Cuboid(point, point),
                List.of(new OutputContainer(OutputContainer.Type.BARREL, Set.of(new BlockPosition(world, 5, 64, 0))),
                        new OutputContainer(OutputContainer.Type.CHEST, Set.of(new BlockPosition(world, 3, 64, 0))),
                        new OutputContainer(OutputContainer.Type.CHEST, Set.of(new BlockPosition(world, 4, 70, 0)))),
                State.STASIS, new ProductionProfile(List.of(Map.of(Material.IRON_INGOT, 1.5),
                        Map.of(), Map.of(Material.POPPY, 0.25, Material.IRON_INGOT, 0.5)), 1200));
        var store = new FileSnapshotStore(directory, "26.3");
        store.save(new SavedSnapshot(farm, new byte[] { 1 }));
        assertEquals(farm.withName(null), new FileSnapshotStore(directory, "26.3").loadActive().getFirst().farm());
    }

    @Test
    void manifestOfTheFirstFormatLoadsAsSingleOutputFarm() throws Exception {
        var world = UUID.randomUUID();
        var id = UUID.randomUUID();
        var creator = UUID.randomUUID();
        var farmDirectory = Files.createDirectories(directory.resolve(id.toString()));
        byte[] data = { 7, 8 };
        Files.write(farmDirectory.resolve("snapshot.bin"), data);
        Files.writeString(farmDirectory.resolve("stasis.yml"), String.join("\n",
                "format-version: 1", "minecraft-version: '26.3'", "id: " + id, "creator: " + creator,
                "world: " + world, "min: -1,64,0", "max: 0,65,1",
                "output:", "  type: CHEST", "  blocks:", "  - 3,64,0",
                "profile:", "  useful-ticks: 3600", "  rates:", "    KELP: 0.5",
                "sha256: " + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)),
                "binary-file: snapshot.bin", ""));
        var loaded = new FileSnapshotStore(directory, "26.3").loadActive().getFirst();
        assertEquals(new MarkedFarm(id, creator, new Cuboid(new BlockPosition(world, -1, 64, 0),
                        new BlockPosition(world, 0, 65, 1)),
                new OutputContainer(OutputContainer.Type.CHEST, Set.of(new BlockPosition(world, 3, 64, 0))),
                State.STASIS, new ProductionProfile(Map.of(Material.KELP, 0.5), 3600)), loaded.farm());
        assertArrayEquals(data, loaded.data());
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
