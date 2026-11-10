package io.github.checkselfy.bakemechanisms.stasis;

/** Retryable before any snapshot is published or any world data is changed. */
public final class PistonInMotionException extends IllegalArgumentException {
    public PistonInMotionException() {
        super("Поршни ещё движутся. Остановите механизм и повторите стазис.");
    }
}
