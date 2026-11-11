package io.github.checkselfy.bakemechanisms.emulation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Material;

/**
 * Everything {@link ProfileEmulation} needs to continue after a restart without losing or re-crediting output.
 *
 * @param outputs progress of every output container, in the farm's output order; empty for a fresh start
 */
public record EmulationState(List<OutputState> outputs) {
    /** No progress yet: valid for any profile. */
    public static final EmulationState EMPTY = new EmulationState(List.of());

    /**
     * @param fractions fractional accumulators, each in {@code [0, 1)}
     * @param buffer whole items produced for this output but not yet placed into it, each positive
     */
    public record OutputState(Map<Material, BigDecimal> fractions, Map<Material, Integer> buffer) {
        public OutputState {
            fractions = Map.copyOf(Objects.requireNonNull(fractions, "fractions"));
            buffer = Map.copyOf(Objects.requireNonNull(buffer, "buffer"));
            for (var fraction : fractions.values()) {
                if (fraction.signum() < 0 || fraction.compareTo(BigDecimal.ONE) >= 0) {
                    throw new IllegalArgumentException("Дробный накопитель вне [0, 1): " + fraction);
                }
            }
            for (var amount : buffer.values()) {
                if (amount < 1) {
                    throw new IllegalArgumentException(
                            "Количество предметов в буфере выработки должно быть положительным.");
                }
            }
        }
    }

    public EmulationState {
        outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
    }

    /** State of a farm with a single output container. */
    public EmulationState(Map<Material, BigDecimal> fractions, Map<Material, Integer> buffer) {
        this(List.of(new OutputState(fractions, buffer)));
    }
}
