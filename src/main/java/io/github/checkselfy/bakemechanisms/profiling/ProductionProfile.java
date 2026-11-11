package io.github.checkselfy.bakemechanisms.profiling;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Material;

/**
 * Average production of each output container by normalized item type. Experience is never profiled.
 *
 * @param outputs items per 20 server ticks for each output container, in the farm's output order; an output that
 *                received nothing has no rates
 * @param usefulTicks length of the measured segment, common to all outputs, before any of them filled up
 */
public record ProductionProfile(List<Map<Material, Double>> outputs, long usefulTicks) {
    public ProductionProfile {
        outputs = Objects.requireNonNull(outputs, "outputs").stream().map(Map::copyOf).toList();
        if (outputs.isEmpty()) throw new IllegalArgumentException("Профиль без выходных контейнеров.");
        if (outputs.stream().allMatch(Map::isEmpty)) throw new IllegalArgumentException("Профиль без продукции.");
        for (var rates : outputs) {
            for (var rate : rates.values()) {
                if (!Double.isFinite(rate) || rate <= 0) {
                    throw new IllegalArgumentException("Скорость профиля должна быть положительной: " + rate);
                }
            }
        }
        if (usefulTicks < 1) throw new IllegalArgumentException("Полезный участок должен быть положительным.");
    }

    /** Profile of a farm with a single output container. */
    public ProductionProfile(Map<Material, Double> ratesPer20Ticks, long usefulTicks) {
        this(List.of(Objects.requireNonNull(ratesPer20Ticks, "ratesPer20Ticks")), usefulTicks);
    }

    /** Rates summed over all output containers. */
    public Map<Material, Double> ratesPer20Ticks() {
        var total = new EnumMap<Material, Double>(Material.class);
        for (var rates : outputs) rates.forEach((type, rate) -> total.merge(type, rate, Double::sum));
        return Map.copyOf(total);
    }
}
