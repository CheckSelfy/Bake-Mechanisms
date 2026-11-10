package io.github.checkselfy.bakemechanisms.profiling;

/**
 * @param snapshotInterval ticks between output snapshots
 * @param minUsefulTicks shortest acceptable useful segment when the check is enabled
 * @param rejectShortUsefulSegment feature flag for the minimal useful segment check
 */
public record ProfilingSettings(int snapshotInterval, long minUsefulTicks, boolean rejectShortUsefulSegment) {
    /** Fixed in the MVP. */
    public static final int DURATION_TICKS = 3600;
    public static final ProfilingSettings DEFAULT = new ProfilingSettings(20, 600, false);

    public ProfilingSettings {
        if (snapshotInterval < 1 || snapshotInterval > DURATION_TICKS) {
            throw new IllegalArgumentException("Интервал снимков должен быть от 1 до " + DURATION_TICKS + " тиков.");
        }
        if (minUsefulTicks < 1) throw new IllegalArgumentException("Минимальный полезный участок должен быть положительным.");
    }
}
