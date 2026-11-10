package io.github.checkselfy.bakemechanisms.emulation;

/** @param updateInterval server ticks between output updates, independent of profiling snapshots */
public record EmulationSettings(int updateInterval) {
    public static final EmulationSettings DEFAULT = new EmulationSettings(20);

    public EmulationSettings {
        if (updateInterval < 1) throw new IllegalArgumentException("Интервал эмуляции должен быть положительным.");
    }
}
