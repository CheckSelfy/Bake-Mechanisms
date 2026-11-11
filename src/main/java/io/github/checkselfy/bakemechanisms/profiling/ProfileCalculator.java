package io.github.checkselfy.bakemechanisms.profiling;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Material;

/**
 * Turns output snapshots of one profiling run into a production profile. Every output container is measured
 * separately, over one common useful segment. The segment starts at the first snapshot in which the combined
 * hopper/output stock has grown relative to startup, so time while the farm was still switched off is not counted. It
 * ends at the last snapshot before any output fills up: after that, a farm may overflow into the other outputs,
 * and their contents no longer reflect the production rate. Initial hopper stock may move freely without creating
 * production. Net stock growth also includes new items still queued in hoppers. Ambiguous routing of a shared
 * hopper material to multiple outputs is rejected instead of guessing a production split.
 */
public final class ProfileCalculator {
    public sealed interface Result {
        record Accepted(ProductionProfile profile) implements Result {}
        record Rejected(String reason) implements Result {}
    }

    private final List<OutputSnapshot> baseline;
    private final ProfilingSettings settings;
    private final Map<Material, Long> baselineHoppers;
    private Map<Material, Long> firstHoppers;
    private Map<Material, Long> lastHoppers;
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
        this(baseline, Map.of(), settings);
    }

    public ProfileCalculator(List<OutputSnapshot> baseline, Map<Material, Long> hoppers, ProfilingSettings settings) {
        this.baseline = List.copyOf(Objects.requireNonNull(baseline, "baseline"));
        if (this.baseline.isEmpty()) throw new IllegalArgumentException("Нет выходных контейнеров.");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.baselineHoppers = Map.copyOf(hoppers);
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
        record(elapsedTicks, snapshots, Map.of());
    }

    public void record(long elapsedTicks, List<OutputSnapshot> snapshots, Map<Material, Long> hoppers) {
        if (snapshots.size() != baseline.size()) {
            throw new IllegalArgumentException("Ожидается " + baseline.size() + " снимков выходов.");
        }
        if (saturated()) return;
        fullOutput = firstFull(snapshots);
        if (saturated()) return;
        if (first == null) {
            var initial = totals(baseline, baselineHoppers);
            if (totals(snapshots, hoppers).entrySet().stream()
                    .noneMatch(entry -> entry.getValue() > initial.getOrDefault(entry.getKey(), 0L))) return;
            first = List.copyOf(snapshots);
            firstHoppers = Map.copyOf(hoppers);
            firstTick = elapsedTicks;
        }
        lastUseful = List.copyOf(snapshots);
        lastHoppers = Map.copyOf(hoppers);
        lastUsefulTick = elapsedTicks;
    }

    public Result result() {
        if (first == null) {
            return new Result.Rejected("За время профилирования до заполнения выходного контейнера не произведено"
                    + " ни одного предмета сверх начального суммарного запаса воронок и выходов.");
        }
        long usefulTicks = lastUsefulTick - firstTick;
        if (usefulTicks == 0) {
            return new Result.Rejected("После появления первых предметов не удалось измерить ни одного интервала"
                    + " производства до заполнения выходного контейнера или конца профилирования.");
        }
        var outputs = new ArrayList<Map<Material, Double>>();
        for (int i = 0; i < baseline.size(); i++) outputs.add(new HashMap<>());
        var initial = totals(baseline, baselineHoppers);
        var from = totals(first, firstHoppers);
        var to = totals(lastUseful, lastHoppers);
        for (var entry : to.entrySet()) {
            var type = entry.getKey();
            // Never count movement of initial stock, even if it left the measured inventories temporarily.
            long produced = Math.min(entry.getValue() - from.getOrDefault(type, 0L),
                    entry.getValue() - initial.getOrDefault(type, 0L));
            if (produced <= 0) continue;
            if (outputs.size() == 1) {
                outputs.getFirst().put(type, produced * 20.0 / usefulTicks);
                continue;
            }
            var destinations = new ArrayList<Integer>();
            long weight = 0;
            for (int i = 0; i < outputs.size(); i++) {
                long gain = Math.max(0L, (long) lastUseful.get(i).count(type) - first.get(i).count(type));
                if (gain > 0) { destinations.add(i); weight += gain; }
            }
            boolean hopperStock = baselineHoppers.getOrDefault(type, 0L) > 0
                    || firstHoppers.getOrDefault(type, 0L) > 0 || lastHoppers.getOrDefault(type, 0L) > 0;
            if (destinations.isEmpty() || destinations.size() > 1 && hopperStock) {
                return new Result.Rejected("Нельзя однозначно распределить прирост " + type
                        + " между выходными контейнерами с учётом запаса воронок. Разделите выходы по типам продукции.");
            }
            for (int index : destinations) {
                long gain = (long) lastUseful.get(index).count(type) - first.get(index).count(type);
                outputs.get(index).put(type, produced * 20.0 / usefulTicks * gain / weight);
            }
        }
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

    private static Map<Material, Long> totals(List<OutputSnapshot> outputs, Map<Material, Long> hoppers) {
        var total = new HashMap<>(hoppers);
        for (var output : outputs) output.counts().forEach((type, amount) -> total.merge(type, (long) amount, Long::sum));
        return total;
    }

    private static int firstFull(List<OutputSnapshot> snapshots) {
        for (int i = 0; i < snapshots.size(); i++) {
            if (snapshots.get(i).full()) return i;
        }
        return -1;
    }
}
