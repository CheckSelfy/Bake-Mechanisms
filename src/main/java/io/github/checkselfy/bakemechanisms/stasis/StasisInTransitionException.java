package io.github.checkselfy.bakemechanisms.stasis;

/**
 * The mechanism is between two stable states (a piston stroke, an entity being spawned, killed, mounted or
 * dismounted). Thrown before any snapshot is published or any world data is changed, so the caller may simply
 * retry on a later tick.
 */
public class StasisInTransitionException extends IllegalArgumentException {
    public StasisInTransitionException(String message) {
        super(message);
    }
}
