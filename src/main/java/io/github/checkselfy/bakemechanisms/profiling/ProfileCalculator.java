package io.github.checkselfy.bakemechanisms.profiling;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Material;

/**
 * Turns output snapshots of one profiling run into a production profile. Every output container is measured
 * separately, over one common useful segment. The segment starts at the first snapshot in which any output holds
 * more items than at the start of profiling, so time while the farm was still switched off is not counted. It
 * ends at the last snapshot before any output fills up: after that, a farm may overflow into the other outputs,
 * and their contents no longer reflect the production rate.
 */
public final class ProfileCalculator {
    public sealed interface Result {
        record Accepted(ProductionProfile profile) implements Result {}
        record Rejected(String reason) implements Result {}
    }

    private final List<OutputSnapshot> baseline;
    private final ProfilingSettings settings;
    /** Contents when the first items appeared; null while the farm has produced nothing. */
    private List<OutputSnapshot> first;
    private long firstTick;
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
        if (first == null) {
            if (rates(baseline, snapshots, 1).stream().allMatch(Map::isEmpty)) return;
            first = List.copyOf(snapshots);
            firstTick = elapsedTicks;
        }
        lastUseful = List.copyOf(snapshots);
        lastUsefulTick = elapsedTicks;
    }

    public Result result() {
        if (first == null) {
            return new Result.Rejected("За время профилирования до заполнения выходного контейнера не появилось"
                    + " ни одного предмета.");
        }
        long usefulTicks = lastUsefulTick - firstTick;
        if (usefulTicks == 0) {
            return new Result.Rejected("После появления первых предметов не удалось измерить ни одного интервала"
                    + " производства до заполнения выходного контейнера или конца профилирования.");
        }
        var outputs = rates(first, lastUseful, usefulTicks);
        if (outputs.stream().allMatch(Map::isEmpty)) {
            return new Result.Rejected("За полезный участок (" + usefulTicks
                    + " тиков) не произведено ни одного предмета.");
        }
        if (settings.rejectShortUsefulSegment() && usefulTicks < settings.minUsefulTicks()) {
            return new Result.Rejected("Полезный участок " + usefulTicks + " тиков короче минимума "
                    + settings.minUsefulTicks() + " тиков: выходной контейнер заполнился слишком быстро или ферма"
                    + " начала производство слишком поздно.");
        }
        return new Result.Accepted(new ProductionProfile(outputs, usefulTicks));
    }

    /** Items per 20 ticks gained by every output between two snapshots taken {@code ticks} apart. */
    private static List<Map<Material, Double>> rates(List<OutputSnapshot> from, List<OutputSnapshot> to,
                                                     long ticks) {
        var outputs = new ArrayList<Map<Material, Double>>();
        for (int i = 0; i < from.size(); i++) {
            var rates = new HashMap<Material, Double>();
            for (var entry : to.get(i).counts().entrySet()) {
                int produced = entry.getValue() - from.get(i).count(entry.getKey());
                if (produced > 0) rates.put(entry.getKey(), produced * 20.0 / ticks);
            }
            outputs.add(rates);
        }
        return outputs;
    }

    private static int firstFull(List<OutputSnapshot> snapshots) {
        for (int i = 0; i < snapshots.size(); i++) {
            if (snapshots.get(i).full()) return i;
        }
        return -1;
    }
}
