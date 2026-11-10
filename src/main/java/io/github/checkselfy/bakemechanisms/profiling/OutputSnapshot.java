package io.github.checkselfy.bakemechanisms.profiling;

import java.util.Map;
import java.util.Objects;
import org.bukkit.Material;

/**
 * Output inventory contents reduced to normalized item types.
 *
 * @param full true when no slot can accept any more items
 */
public record OutputSnapshot(Map<Material, Integer> counts, boolean full) {
    public OutputSnapshot {
        counts = Map.copyOf(Objects.requireNonNull(counts, "counts"));
    }

    public int count(Material type) {
        return counts.getOrDefault(type, 0);
    }
}
