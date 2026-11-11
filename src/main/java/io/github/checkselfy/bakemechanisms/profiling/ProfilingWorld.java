package io.github.checkselfy.bakemechanisms.profiling;

import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.marking.OutputContainer;
import java.util.UUID;

/** Everything profiling needs from the server. Called on the server thread only. */
public interface ProfilingWorld extends ChunkTickets.Backend {
    /** Validates initial sources and closes inventories throughout the protection belt. */
    default ProfilingIsolation.Sources validateIsolation(io.github.checkselfy.bakemechanisms.marking.FarmRegistry.MarkedFarm farm) {
        return ProfilingIsolation.Sources.empty();
    }
    /** @throws IllegalStateException when the output is no longer the registered container */
    OutputSnapshot readOutput(OutputContainer output);

    boolean hasPlayerInside(Cuboid mechanism);

    /** Closes the output for players who already have it open. */
    void lockOutput(OutputContainer output);

    /** Delivers a message if the player is online. */
    void notify(UUID playerId, String message);
}
