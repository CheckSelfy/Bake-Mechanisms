package io.github.checkselfy.bakemechanisms.emulation;

import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;

/** Where emulation progress is persisted between restarts. Called on the server thread. */
public interface EmulationStore {
    /** Fractional accumulators and buffer of a farm in stasis; valid only beside its profile. */
    void saveEmulation(UUID farmId, EmulationState state);

    /** Leftover buffer of a restored farm, kept independently of any profile until it is delivered. */
    void saveDrain(UUID farmId, Map<Material, Integer> buffer);

    void deleteDrain(UUID farmId);
}
