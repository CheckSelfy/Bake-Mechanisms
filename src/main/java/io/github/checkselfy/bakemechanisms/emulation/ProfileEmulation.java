package io.github.checkselfy.bakemechanisms.emulation;

import io.github.checkselfy.bakemechanisms.profiling.ProductionProfile;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;
import org.bukkit.Material;

/**
 * Produces items from a profile, separately for every output container. Not thread-safe; call on the server
 * thread.
 */
public final class ProfileEmulation {
    /** Destination of produced items, normally the farm's output containers. */
    public interface Output {
        /**
         * @param output index of the output container in the farm's output order
         * @return how many items were accepted, from 0 to {@code amount}
         * @throws IllegalStateException when the output no longer exists
         */
        int insert(int output, Material type, int amount);
    }

    /** Rates are expressed per this many server ticks, see {@link ProductionProfile#outputs()}. */
    private static final BigDecimal RATE_WINDOW_TICKS = BigDecimal.valueOf(20);

    private final List<Map<Material, BigDecimal>> rates = new ArrayList<>();
    private final List<Map<Material, BigDecimal>> fractions = new ArrayList<>();
    private final List<Map<Material, Integer>> buffers = new ArrayList<>();

    public ProfileEmulation(ProductionProfile profile) {
        this(profile, EmulationState.EMPTY);
    }

    /**
     * Continues from a saved {@link #state()}.
     *
     * @throws IllegalArgumentException when the state has another number of outputs or holds item types the
     *                                  profile does not produce in that output
     */
    public ProfileEmulation(ProductionProfile profile, EmulationState state) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(state, "state");
        int count = profile.outputs().size();
        if (!state.outputs().isEmpty() && state.outputs().size() != count) {
            throw new IllegalArgumentException("Состояние эмуляции не соответствует профилю.");
        }
        for (int i = 0; i < count; i++) {
            var saved = state.outputs().isEmpty() ? new EmulationState.OutputState(Map.of(), Map.of())
                    : state.outputs().get(i);
            var outputRates = new EnumMap<Material, BigDecimal>(Material.class);
            var outputFractions = new EnumMap<Material, BigDecimal>(Material.class);
            profile.outputs().get(i).forEach((type, rate) -> {
                outputRates.put(type, BigDecimal.valueOf(rate));
                outputFractions.put(type, saved.fractions().getOrDefault(type, BigDecimal.ZERO));
            });
            if (!outputRates.keySet().containsAll(saved.fractions().keySet())
                    || !outputRates.keySet().containsAll(saved.buffer().keySet())) {
                throw new IllegalArgumentException("Состояние эмуляции не соответствует профилю.");
            }
            var buffer = new EnumMap<Material, Integer>(Material.class);
            buffer.putAll(saved.buffer());
            rates.add(outputRates);
            fractions.add(outputFractions);
            buffers.add(buffer);
        }
    }

    /** Snapshot for persistence; restoring it with the same profile continues production exactly. */
    public EmulationState state() {
        return new EmulationState(IntStream.range(0, rates.size())
                .mapToObj(i -> new EmulationState.OutputState(fractions.get(i), buffers.get(i))).toList());
    }

    /**
     * Moves every buffer into its output, then produces {@code ticks} server ticks of the profile unless some
     * buffer is still waiting for space. One full output pauses production for all of them, while the others
     * still receive what was already produced for them. Production is paused rather than stored, so no buffer
     * ever holds more than one update's output that did not fit.
     *
     * @throws IllegalStateException when an output no longer exists; every other output was still served
     */
    public void update(long ticks, Output output) {
        if (ticks < 1) throw new IllegalArgumentException("ticks must be positive");
        Objects.requireNonNull(output, "output");
        deliver(buffers, output);
        if (paused()) return;
        for (int i = 0; i < rates.size(); i++) {
            for (var entry : rates.get(i).entrySet()) {
                var type = entry.getKey();
                // Exact decimal arithmetic: the rate is never rounded and fractions never drift.
                var total = fractions.get(i).get(type)
                        .add(entry.getValue().multiply(BigDecimal.valueOf(ticks)).divide(RATE_WINDOW_TICKS));
                var whole = total.setScale(0, RoundingMode.FLOOR);
                fractions.get(i).put(type, total.subtract(whole));
                if (whole.signum() > 0) buffers.get(i).merge(type, whole.intValueExact(), Integer::sum);
            }
        }
        deliver(buffers, output);
    }

    /** True while produced items wait for space in some output; no new items are produced meanwhile. */
    public boolean paused() {
        return buffers.stream().anyMatch(buffer -> !buffer.isEmpty());
    }

    /** Indices of the outputs whose produced items wait for space. */
    public List<Integer> blockedOutputs() {
        return IntStream.range(0, buffers.size()).filter(i -> !buffers.get(i).isEmpty()).boxed().toList();
    }

    /** Whole items produced but not yet placed, summed over all outputs. */
    public Map<Material, Integer> buffer() {
        var total = new EnumMap<Material, Integer>(Material.class);
        for (var buffer : buffers) buffer.forEach((type, amount) -> total.merge(type, amount, Integer::sum));
        return Map.copyOf(total);
    }

    /** Whole items produced but not yet placed, for every output in order. */
    public List<Map<Material, Integer>> buffers() {
        return buffers.stream().map(Map::copyOf).toList();
    }

    /**
     * Moves as much of every buffer into its output as fits, removing what was accepted. An output that is gone
     * keeps its items and does not stop delivery to the others.
     *
     * @throws IllegalStateException the first failure, after every output was served
     */
    static void deliver(List<Map<Material, Integer>> buffers, Output output) {
        IllegalStateException failure = null;
        for (int i = 0; i < buffers.size(); i++) {
            try {
                deliver(i, buffers.get(i), output);
            } catch (IllegalStateException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    private static void deliver(int index, Map<Material, Integer> buffer, Output output) {
        for (var iterator = buffer.entrySet().iterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            int accepted = output.insert(index, entry.getKey(), entry.getValue());
            if (accepted < 0 || accepted > entry.getValue()) {
                throw new IllegalStateException("Выходной контейнер принял " + accepted + " из " + entry.getValue()
                        + " предметов.");
            }
            if (accepted == entry.getValue()) iterator.remove();
            else entry.setValue(entry.getValue() - accepted);
        }
    }
}
