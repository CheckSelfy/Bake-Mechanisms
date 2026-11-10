package io.github.checkselfy.bakemechanisms.emulation;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Material;

/**
 * Everything {@link ProfileEmulation} needs to continue after a restart without losing or re-crediting output.
 *
 * @param fractions fractional accumulators, each in {@code [0, 1)}
 * @param buffer whole items produced but not yet placed into the output, each positive
 */
public record EmulationState(Map<Material, BigDecimal> fractions, Map<Material, Integer> buffer) {
    public EmulationState {
        fractions = Map.copyOf(Objects.requireNonNull(fractions, "fractions"));
        buffer = Map.copyOf(Objects.requireNonNull(buffer, "buffer"));
        for (var fraction : fractions.values()) {
            if (fraction.signum() < 0 || fraction.compareTo(BigDecimal.ONE) >= 0) {
                throw new IllegalArgumentException("Дробный накопитель вне [0, 1): " + fraction);
            }
        }
        for (var amount : buffer.values()) {
            if (amount < 1) {
                throw new IllegalArgumentException("Количество предметов в буфере выработки должно быть положительным.");
            }
        }
    }
}
