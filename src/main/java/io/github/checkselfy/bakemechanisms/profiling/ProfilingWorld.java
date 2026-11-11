package io.github.checkselfy.bakemechanisms.profiling;

import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.marking.OutputContainer;
import java.util.UUID;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import io.github.checkselfy.bakemechanisms.marking.BlockPosition;

/** Everything profiling needs from the server. Called on the server thread only. */
public interface ProfilingWorld extends ChunkTickets.Backend {
    /** Validates initial sources and closes inventories throughout the protection belt. */
    default ProfilingIsolation.Sources validateIsolation(io.github.checkselfy.bakemechanisms.marking.FarmRegistry.MarkedFarm farm) {
        return ProfilingIsolation.Sources.empty();
    }
    /** @throws IllegalStateException when the output is no longer the registered container */
    OutputSnapshot readOutput(OutputContainer output);

    /** Contents of the same internal block hoppers throughout one run; missing hoppers must fail the run. */
    default Map<Material, Long> readHoppers(Set<BlockPosition> hoppers) { return Map.of(); }

    /** Called only after startup validation and baseline reads succeed. Deletes loose items inside the mechanism. */
    default void removeLooseItems(Cuboid mechanism) { }

    boolean hasPlayerInside(Cuboid mechanism);

    /** Closes the output for players who already have it open. */
    void lockOutput(OutputContainer output);

    /** Delivers a message if the player is online. */
    void notify(UUID playerId, String message);
}
