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
        var profiling = new MarkedFarm(ready.id(), ready.creatorId(), ready.mechanism(), ready.output(),
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
    void missingRootMeansNoFarms() {
        assertEquals(List.of(), new FarmStorage(root.resolve("absent")).loadAll());
    }

    private static Cuboid mechanism() {
        return new Cuboid(new BlockPosition(WORLD, 0, 60, 0), new BlockPosition(WORLD, 4, 64, 4));
    }

    private static OutputContainer chest() {
        return new OutputContainer(OutputContainer.Type.CHEST, Set.of(new BlockPosition(WORLD, 10, 60, 0)));
    }
}
