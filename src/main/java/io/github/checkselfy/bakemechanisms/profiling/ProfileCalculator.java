package io.github.checkselfy.bakemechanisms.profiling;

import java.util.HashMap;
import java.util.Objects;
import org.bukkit.Material;

/** Turns output snapshots of one profiling run into a production profile. */
public final class ProfileCalculator {
    public sealed interface Result {
        record Accepted(ProductionProfile profile) implements Result {}
        record Rejected(String reason) implements Result {}
    }

    private final OutputSnapshot baseline;
    private final ProfilingSettings settings;
    private OutputSnapshot lastUseful;
    private long lastUsefulTick;
    private boolean saturated;

    public ProfileCalculator(OutputSnapshot baseline, ProfilingSettings settings) {
        this.baseline = Objects.requireNonNull(baseline, "baseline");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.lastUseful = baseline;
        this.saturated = baseline.full();
    }

    /** Snapshots after the first full one carry no information about the production rate. */
    public boolean saturated() {
        return saturated;
    }

    public void record(long elapsedTicks, OutputSnapshot snapshot) {
        if (saturated) return;
        if (snapshot.full()) {
            saturated = true;
            return;
        }
        lastUseful = snapshot;
        lastUsefulTick = elapsedTicks;
    }

    public Result result() {
        var rates = new HashMap<Material, Double>();
        for (var entry : lastUseful.counts().entrySet()) {
            int produced = entry.getValue() - baseline.count(entry.getKey());
            if (produced > 0) rates.put(entry.getKey(), produced * 20.0 / lastUsefulTick);
        }
        if (rates.isEmpty()) {
            return new Result.Rejected("За полезный участок (" + lastUsefulTick
                    + " тиков) не произведено ни одного предмета.");
        }
        if (settings.rejectShortUsefulSegment() && lastUsefulTick < settings.minUsefulTicks()) {
            return new Result.Rejected("Полезный участок " + lastUsefulTick + " тиков короче минимума "
                    + settings.minUsefulTicks() + " тиков: выходной контейнер заполнился слишком быстро.");
        }
        return new Result.Accepted(new ProductionProfile(rates, lastUsefulTick));
    }
}
