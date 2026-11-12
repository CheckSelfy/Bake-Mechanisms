package io.github.checkselfy.bakemechanisms.stasis;

/** Retryable before any snapshot is published or any world data is changed. */
public final class PistonInMotionException extends TransientStasisException {
    public PistonInMotionException() {
        super("Поршни ещё движутся. Остановите механизм и повторите стазис.");
    }

    /** A piston stroke takes two ticks; a mechanism still moving after this is a running clock. */
    @Override public int maxRetries() {
        return 10;
    }
}
