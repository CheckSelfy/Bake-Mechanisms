package io.github.checkselfy.bakemechanisms.emulation;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;

/** Where emulation progress is persisted between restarts. Called on the server thread. */
public interface EmulationStore {
    /** Fractional accumulators and buffers of a farm in stasis; valid only beside its profile. */
    void saveEmulation(UUID farmId, EmulationState state);

    /**
     * Leftover buffer of a restored farm, one per output container in order, kept independently of any profile
     * until it is delivered.
     */
    void saveDrain(UUID farmId, List<Map<Material, Integer>> buffers);

    void deleteDrain(UUID farmId);
}
