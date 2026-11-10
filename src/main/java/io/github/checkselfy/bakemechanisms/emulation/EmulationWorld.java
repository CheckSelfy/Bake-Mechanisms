package io.github.checkselfy.bakemechanisms.emulation;

import io.github.checkselfy.bakemechanisms.marking.OutputContainer;
import java.util.UUID;
import org.bukkit.Material;

/** Everything emulation needs from the server. Called on the server thread only. */
public interface EmulationWorld {
    /**
     * Adds plain items of {@code type}, without any components, to the output.
     *
     * @return how many items fit, from 0 to {@code amount}
     * @throws IllegalStateException when the output is no longer the registered container
     */
    int insert(OutputContainer output, Material type, int amount);

    /** Delivers a message if the player is online. */
    void notify(UUID playerId, String message);
}
