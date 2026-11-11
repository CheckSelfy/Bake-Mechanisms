package io.github.checkselfy.bakemechanisms.storage;

import static org.junit.jupiter.api.Assertions.*;

import io.github.checkselfy.bakemechanisms.emulation.EmulationState;
import io.github.checkselfy.bakemechanisms.marking.BlockPosition;
import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.MarkedFarm;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.State;
import io.github.checkselfy.bakemechanisms.marking.OutputContainer;
import io.github.checkselfy.bakemechanisms.profiling.ProductionProfile;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FarmStorageTest {
    private static final UUID WORLD = UUID.randomUUID();

    @TempDir
    Path root;

    @Test
    void readyFarmSurvivesAsAHumanReadableFileInItsOwnDirectory() throws IOException {
        var profile = new ProductionProfile(Map.of(Material.KELP, 0.1 + 0.2, Material.BAMBOO, 3.0), 1234);
        var farm = new MarkedFarm(UUID.randomUUID(), UUID.randomUUID(),
                new Cuboid(new BlockPosition(WORLD, -20, -64, 5), new BlockPosition(WORLD, -1, 10, 30)),
                new OutputContainer(OutputContainer.Type.DOUBLE_CHEST,
                        Set.of(new BlockPosition(WORLD, 0, 1, 2), new BlockPosition(WORLD, 1, 1, 2))),
                State.READY, profile);

        new FarmStorage(root).save(farm);

        var file = root.resolve(farm.id().toString()).resolve("farm.yml");
        var text = Files.readString(file);
        assertTrue(text.contains("READY") && text.contains(farm.creatorId().toString())
                && text.contains("minecraft:kelp"), text);
        assertEquals(List.of(new FarmStorage.StoredFarm(farm, Optional.empty())), new FarmStorage(root).loadAll());
    }

    @Test
    void emulationStateIsBinaryAndRestoredExactly() throws IOException {
        var farm = new MarkedFarm(UUID.randomUUID(), UUID.randomUUID(), mechanism(), chest(),
                State.READY, new ProductionProfile(Map.of(Material.KELP, 0.7, Material.BAMBOO, 3.0), 3600));
        var state = new EmulationState(
                Map.of(Material.KELP, new BigDecimal("0.40000000000000000001"), Material.BAMBOO, BigDecimal.ZERO),
                Map.of(Material.BAMBOO, 70_000));
        var storage = new FarmStorage(root);
        storage.save(farm);
        storage.saveEmulation(farm.id(), state);

        var bytes = Files.readAllBytes(root.resolve(farm.id().toString()).resolve("emulation.dat"));
        assertFalse(new String(bytes).contains("70000"), "buffer counts are binary");
        assertEquals(List.of(new FarmStorage.StoredFarm(farm, Optional.of(state))), new FarmStorage(root).loadAll());

        storage.deleteEmulation(farm.id());
        assertEquals(Optional.empty(), storage.loadAll().getFirst().emulation());
    }

    @Test
    void leavingReadyDropsEmulationStateSoAStaleBufferIsNeverReused() {
        var storage = new FarmStorage(root);
        var ready = new MarkedFarm(UUID.randomUUID(), UUID.randomUUID(), mechanism(), chest(),
                State.READY, new ProductionProfile(Map.of(Material.KELP, 1.0), 3600));
        storage.save(ready);
        storage.saveEmulation(ready.id(), new EmulationState(Map.of(), Map.of(Material.KELP, 3)));
        var profiling = new MarkedFarm(ready.id(), ready.creatorId(), ready.mechanism(), ready.outputs().get(0),
                State.PROFILING, null);
        storage.save(profiling);

        assertEquals(List.of(new FarmStorage.StoredFarm(profiling, Optional.empty())), storage.loadAll(),
                "storage keeps facts; the registry decides what PROFILING means after restart");
    }

    @Test
    void emulationStateOfAnotherProfileFailsLoudly() {
        var storage = new FarmStorage(root);
        var farm = new MarkedFarm(UUID.randomUUID(), UUID.randomUUID(), mechanism(), chest(),
                State.READY, new ProductionProfile(Map.of(Material.KELP, 1.0), 3600));
        storage.save(farm);
        storage.saveEmulation(farm.id(), new EmulationState(Map.of(), Map.of(Material.BAMBOO, 3)));
        assertThrows(IllegalStateException.class, storage::loadAll);
    }

    @Test
    void farmWhoseFirstSaveNeverCompletedIsSkipped() throws IOException {
        var dir = Files.createDirectories(root.resolve(UUID.randomUUID().toString()));
        Files.writeString(dir.resolve("farm.yml.tmp"), "format: 1");
        assertEquals(List.of(), new FarmStorage(root).loadAll());
    }

    @Test
    void corruptFarmFileFailsLoudlyInsteadOfBeingDropped() throws IOException {
        var dir = Files.createDirectories(root.resolve(UUID.randomUUID().toString()));
        Files.writeString(dir.resolve("farm.yml"), "state: READY\n");
        var error = assertThrows(IllegalStateException.class, () -> new FarmStorage(root).loadAll());
        assertTrue(error.getMessage().contains(dir.getFileName().toString()), error.getMessage());
    }

    @Test
    void profileOutsideReadyIsRejectedWithAnEnglishConsoleMessage() throws IOException {
        var storage = new FarmStorage(root);
        var ready = new MarkedFarm(UUID.randomUUID(), UUID.randomUUID(), mechanism(), chest(),
                State.READY, new ProductionProfile(Map.of(Material.KELP, 1.0), 3600));
        storage.save(ready);
        var file = root.resolve(ready.id().toString()).resolve("farm.yml");
        Files.writeString(file, Files.readString(file).replace("state: READY", "state: NEEDS_PROFILING"));

        var error = assertThrows(IllegalStateException.class, storage::loadAll);

        assertEquals("Cannot load farm " + ready.id() + " from " + file
                + ": state NEEDS_PROFILING must not have a profile (only READY and STASIS have one)", error.getMessage());
    }

    @Test
    void stasisFarmKeepsItsProfileAcrossRestarts() {
        var storage = new FarmStorage(root);
        var farm = new MarkedFarm(UUID.randomUUID(), UUID.randomUUID(), mechanism(), chest(),
                State.STASIS, new ProductionProfile(Map.of(Material.KELP, 1.0), 3600));
        storage.save(farm);

        assertEquals(List.of(new FarmStorage.StoredFarm(farm, Optional.empty())), storage.loadAll());
    }

    @Test
    void leftoverBufferOfARestoredFarmSurvivesLosingTheProfile() throws IOException {
        var storage = new FarmStorage(root);
        var stasis = new MarkedFarm(UUID.randomUUID(), UUID.randomUUID(), mechanism(), chest(),
                State.STASIS, new ProductionProfile(Map.of(Material.KELP, 1.0), 3600));
        storage.save(stasis);
        storage.saveEmulation(stasis.id(), new EmulationState(Map.of(), Map.of(Material.KELP, 3)));
        storage.saveDrain(stasis.id(), List.of(Map.of(Material.KELP, 3)));
        var restored = new MarkedFarm(stasis.id(), stasis.creatorId(), stasis.mechanism(), stasis.outputs().get(0),
                State.NEEDS_PROFILING, null);
        storage.save(restored);

        var bytes = Files.readAllBytes(root.resolve(stasis.id().toString()).resolve("drain.dat"));
        assertFalse(new String(bytes).contains("kelp 3"), "leftover buffer is binary");
        assertEquals(List.of(new FarmStorage.StoredFarm(restored, Optional.empty(), List.of(Map.of(Material.KELP, 3)))),
                new FarmStorage(root).loadAll());

        storage.deleteDrain(stasis.id());
        assertEquals(List.of(new FarmStorage.StoredFarm(restored, Optional.empty())), storage.loadAll());
    }

    @Test
    void corruptLeftoverBufferFailsLoudly() throws IOException {
        var storage = new FarmStorage(root);
        var farm = new MarkedFarm(UUID.randomUUID(), UUID.randomUUID(), mechanism(), chest(),
                State.NEEDS_PROFILING, null);
        storage.save(farm);
        Files.write(root.resolve(farm.id().toString()).resolve("drain.dat"), new byte[] { 1, 2, 3 });
        var error = assertThrows(IllegalStateException.class, storage::loadAll);
        assertTrue(error.getMessage().contains("drain.dat"), error.getMessage());
    }

    @Test
    void missingRootMeansNoFarms() {
        assertEquals(List.of(), new FarmStorage(root.resolve("absent")).loadAll());
    }

    @Test
    void deleteRemovesEveryFileOfTheFarmAndOnlyThatFarm() throws IOException {
        var storage = new FarmStorage(root);
        var farm = new MarkedFarm(UUID.randomUUID(), UUID.randomUUID(), mechanism(), chest(),
                State.READY, new ProductionProfile(Map.of(Material.KELP, 1.0), 3600));
        var other = new MarkedFarm(UUID.randomUUID(), UUID.randomUUID(), mechanism(), chest(),
                State.NEEDS_PROFILING, null);
        storage.save(farm);
        storage.saveEmulation(farm.id(), new EmulationState(Map.of(), Map.of(Material.KELP, 2)));
        storage.saveDrain(farm.id(), List.of(Map.of(Material.KELP, 3)));
        Files.write(root.resolve(farm.id().toString()).resolve("snapshot.bin"), new byte[] { 1 });
        storage.save(other);

        storage.delete(farm.id());

        assertFalse(Files.exists(root.resolve(farm.id().toString())));
        assertEquals(List.of(new FarmStorage.StoredFarm(other, Optional.empty())), storage.loadAll());
        assertDoesNotThrow(() -> storage.delete(farm.id()), "nothing left to delete");
    }

    @Test
    void deleteNeverTouchesAFarmWithAnActiveSnapshot() throws IOException {
        var storage = new FarmStorage(root);
        var farm = new MarkedFarm(UUID.randomUUID(), UUID.randomUUID(), mechanism(), chest(),
                State.STASIS, new ProductionProfile(Map.of(Material.KELP, 1.0), 3600));
        storage.save(farm);
        var directory = root.resolve(farm.id().toString());
        Files.write(directory.resolve("snapshot.bin"), new byte[] { 1 });
        Files.writeString(directory.resolve("stasis.yml"), "id: " + farm.id());

        assertThrows(IllegalStateException.class, () -> storage.delete(farm.id()));

        assertTrue(Files.exists(directory.resolve("snapshot.bin")));
        assertTrue(Files.exists(directory.resolve("stasis.yml")));
        assertTrue(Files.exists(directory.resolve("farm.yml")));
    }

    @Test
    void namedFarmWithSeveralOutputsKeepsTheirOrderRatesProgressAndLeftovers() {
        var barrel = new OutputContainer(OutputContainer.Type.BARREL, Set.of(new BlockPosition(WORLD, 12, 60, 0)));
        var farm = new MarkedFarm(UUID.randomUUID(), UUID.randomUUID(), "Iron_farm-2", mechanism(),
                List.of(barrel, chest()), State.STASIS, new ProductionProfile(
                        List.of(Map.of(Material.IRON_INGOT, 2.5), Map.of(Material.POPPY, 0.125)), 900));
        var state = new EmulationState(List.of(
                new EmulationState.OutputState(Map.of(Material.IRON_INGOT, new BigDecimal("0.5")),
                        Map.of(Material.IRON_INGOT, 4)),
                new EmulationState.OutputState(Map.of(Material.POPPY, new BigDecimal("0.875")), Map.of())));
        var storage = new FarmStorage(root);
        storage.save(farm);
        storage.saveEmulation(farm.id(), state);
        storage.saveDrain(farm.id(), List.of(Map.of(), Map.of(Material.POPPY, 2)));

        assertEquals(List.of(new FarmStorage.StoredFarm(farm, Optional.of(state),
                List.of(Map.of(), Map.of(Material.POPPY, 2)))), new FarmStorage(root).loadAll());
    }

    @Test
    void filesOfTheFirstFormatLoadAsAnUnnamedSingleOutputFarm() throws IOException {
        var id = UUID.randomUUID();
        var creator = UUID.randomUUID();
        var dir = Files.createDirectories(root.resolve(id.toString()));
        Files.writeString(dir.resolve("farm.yml"), String.join("\n",
                "format: 1", "id: " + id, "creator: " + creator, "state: STASIS", "world: " + WORLD,
                "mechanism:", "  min: [0, 60, 0]", "  max: [4, 64, 4]",
                "output:", "  type: CHEST", "  blocks:", "  - [10, 60, 0]",
                "profile:", "  useful-ticks: 3600", "  rates-per-20-ticks:", "    minecraft:kelp: 0.5", ""));
        var emulation = new java.io.ByteArrayOutputStream();
        try (var out = new java.io.DataOutputStream(emulation)) {
            out.writeInt(0x42414b45); // "BAKE"
            out.writeInt(1);
            out.writeInt(1);
            out.writeUTF("minecraft:kelp");
            out.writeUTF("0.25");
            out.writeInt(1);
            out.writeUTF("minecraft:kelp");
            out.writeInt(6);
        }
        Files.write(dir.resolve("emulation.dat"), emulation.toByteArray());
        var drain = new java.io.ByteArrayOutputStream();
        try (var out = new java.io.DataOutputStream(drain)) {
            out.writeInt(0x4452414e); // "DRAN"
            out.writeInt(1);
            out.writeInt(1);
            out.writeUTF("minecraft:bone");
            out.writeInt(9);
        }
        Files.write(dir.resolve("drain.dat"), drain.toByteArray());

        var farm = new MarkedFarm(id, creator, mechanism(), chest(), State.STASIS,
                new ProductionProfile(Map.of(Material.KELP, 0.5), 3600));
        assertEquals(List.of(new FarmStorage.StoredFarm(farm,
                        Optional.of(new EmulationState(Map.of(Material.KELP, new BigDecimal("0.25")),
                                Map.of(Material.KELP, 6))),
                        List.of(Map.of(Material.BONE, 9)))),
                new FarmStorage(root).loadAll());
    }

    private static Cuboid mechanism() {
        return new Cuboid(new BlockPosition(WORLD, 0, 60, 0), new BlockPosition(WORLD, 4, 64, 4));
    }

    private static OutputContainer chest() {
        return new OutputContainer(OutputContainer.Type.CHEST, Set.of(new BlockPosition(WORLD, 10, 60, 0)));
    }
}
