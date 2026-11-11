package io.github.checkselfy.bakemechanisms.profiling;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Material;

/**
 * Turns output snapshots of one profiling run into a production profile. Every output container is measured
 * separately, over one common useful segment that ends when any of them fills up: after that, a farm may
 * overflow into the other outputs, and their contents no longer reflect the production rate.
 */
public final class ProfileCalculator {
    public sealed interface Result {
        record Accepted(ProductionProfile profile) implements Result {}
        record Rejected(String reason) implements Result {}
    }

    private final List<OutputSnapshot> baseline;
    private final ProfilingSettings settings;
    private List<OutputSnapshot> lastUseful;
    private long lastUsefulTick;
    private int fullOutput;

    public ProfileCalculator(OutputSnapshot baseline, ProfilingSettings settings) {
        this(List.of(baseline), settings);
    }

    /** @param baseline contents of every output container, in the farm's output order */
    public ProfileCalculator(List<OutputSnapshot> baseline, ProfilingSettings settings) {
        this.baseline = List.copyOf(Objects.requireNonNull(baseline, "baseline"));
        if (this.baseline.isEmpty()) throw new IllegalArgumentException("Нет выходных контейнеров.");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.lastUseful = this.baseline;
        this.fullOutput = firstFull(this.baseline);
    }

    /** Snapshots after the first full one carry no information about the production rate. */
    public boolean saturated() {
        return fullOutput >= 0;
    }

    /** Index of the output whose filling ended the useful segment, or -1 while none has filled. */
    public int fullOutput() {
        return fullOutput;
    }

    public void record(long elapsedTicks, OutputSnapshot snapshot) {
        record(elapsedTicks, List.of(snapshot));
    }

    /** @param snapshots contents of every output container, in the same order as the baseline */
    public void record(long elapsedTicks, List<OutputSnapshot> snapshots) {
        if (snapshots.size() != baseline.size()) {
            throw new IllegalArgumentException("Ожидается " + baseline.size() + " снимков выходов.");
        }
        if (saturated()) return;
        fullOutput = firstFull(snapshots);
        if (saturated()) return;
        lastUseful = List.copyOf(snapshots);
        lastUsefulTick = elapsedTicks;
    }

    public Result result() {
        var outputs = new ArrayList<Map<Material, Double>>();
        for (int i = 0; i < baseline.size(); i++) {
            var rates = new HashMap<Material, Double>();
            for (var entry : lastUseful.get(i).counts().entrySet()) {
                int produced = entry.getValue() - baseline.get(i).count(entry.getKey());
                if (produced > 0) rates.put(entry.getKey(), produced * 20.0 / lastUsefulTick);
            }
            outputs.add(rates);
        }
        if (outputs.stream().allMatch(Map::isEmpty)) {
            return new Result.Rejected("За полезный участок (" + lastUsefulTick
                    + " тиков) не произведено ни одного предмета.");
        }
        if (settings.rejectShortUsefulSegment() && lastUsefulTick < settings.minUsefulTicks()) {
            return new Result.Rejected("Полезный участок " + lastUsefulTick + " тиков короче минимума "
                    + settings.minUsefulTicks() + " тиков: выходной контейнер заполнился слишком быстро.");
        }
        return new Result.Accepted(new ProductionProfile(outputs, lastUsefulTick));
    }

    private static int firstFull(List<OutputSnapshot> snapshots) {
        for (int i = 0; i < snapshots.size(); i++) {
            if (snapshots.get(i).full()) return i;
        }
        return -1;
    }
}
