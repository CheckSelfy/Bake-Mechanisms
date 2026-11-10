package io.github.checkselfy.bakemechanisms.emulation;

import io.github.checkselfy.bakemechanisms.profiling.ProductionProfile;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Material;

/** Produces items from a profile. Not thread-safe; call on the server thread. */
public final class ProfileEmulation {
    /** Destination of produced items, normally the output container. */
    public interface Output {
        /**
         * @return how many items were accepted, from 0 to {@code amount}
         * @throws IllegalStateException when the output no longer exists
         */
        int insert(Material type, int amount);
    }

    /** Rates are expressed per this many server ticks, see {@link ProductionProfile#ratesPer20Ticks()}. */
    private static final BigDecimal RATE_WINDOW_TICKS = BigDecimal.valueOf(20);

    private final Map<Material, BigDecimal> rates = new EnumMap<>(Material.class);
    private final Map<Material, BigDecimal> fractions = new EnumMap<>(Material.class);
    private final Map<Material, Integer> buffer = new EnumMap<>(Material.class);

    public ProfileEmulation(ProductionProfile profile) {
        Objects.requireNonNull(profile, "profile");
        profile.ratesPer20Ticks().forEach((type, rate) -> {
            rates.put(type, BigDecimal.valueOf(rate));
            fractions.put(type, BigDecimal.ZERO);
        });
    }

    /**
     * Moves the buffer into the output, then produces {@code ticks} server ticks of the profile unless the
     * buffer is still waiting for space. Production is paused rather than stored, so the buffer never holds
     * more than one update's output that did not fit.
     */
    public void update(long ticks, Output output) {
        if (ticks < 1) throw new IllegalArgumentException("ticks must be positive");
        Objects.requireNonNull(output, "output");
        flush(output);
        if (paused()) return;
        for (var entry : rates.entrySet()) {
            var type = entry.getKey();
            // Exact decimal arithmetic: the rate is never rounded and fractions never drift.
            var total = fractions.get(type)
                    .add(entry.getValue().multiply(BigDecimal.valueOf(ticks)).divide(RATE_WINDOW_TICKS));
            var whole = total.setScale(0, RoundingMode.FLOOR);
            fractions.put(type, total.subtract(whole));
            if (whole.signum() > 0) buffer.merge(type, whole.intValueExact(), Integer::sum);
        }
        flush(output);
    }

    /** True while produced items wait for space in the output; no new items are produced meanwhile. */
    public boolean paused() {
        return !buffer.isEmpty();
    }

    /** Whole items produced but not yet placed into the output. */
    public Map<Material, Integer> buffer() {
        return Map.copyOf(buffer);
    }

    private void flush(Output output) {
        for (var iterator = buffer.entrySet().iterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            int accepted = output.insert(entry.getKey(), entry.getValue());
            if (accepted < 0 || accepted > entry.getValue()) {
                throw new IllegalStateException("Выходной контейнер принял " + accepted + " из " + entry.getValue()
                        + " предметов.");
            }
            if (accepted == entry.getValue()) iterator.remove();
            else entry.setValue(entry.getValue() - accepted);
        }
    }
}
