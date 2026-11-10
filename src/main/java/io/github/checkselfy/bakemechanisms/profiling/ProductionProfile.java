package io.github.checkselfy.bakemechanisms.profiling;

import java.util.Map;
import java.util.Objects;
import org.bukkit.Material;

/**
 * Average production by normalized item type. Experience is never profiled.
 *
 * @param ratesPer20Ticks items per 20 server ticks, only for types that were produced
 * @param usefulTicks length of the measured segment before the output filled up
 */
public record ProductionProfile(Map<Material, Double> ratesPer20Ticks, long usefulTicks) {
    public ProductionProfile {
        ratesPer20Ticks = Map.copyOf(Objects.requireNonNull(ratesPer20Ticks, "ratesPer20Ticks"));
        if (ratesPer20Ticks.isEmpty()) throw new IllegalArgumentException("Профиль без продукции.");
        if (usefulTicks < 1) throw new IllegalArgumentException("Полезный участок должен быть положительным.");
    }
}
