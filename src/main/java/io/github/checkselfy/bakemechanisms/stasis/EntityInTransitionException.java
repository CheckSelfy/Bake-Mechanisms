package io.github.checkselfy.bakemechanisms.stasis;

/** An entity of the mechanism is being spawned, killed, discarded, mounted or dismounted in this tick. */
public final class EntityInTransitionException extends StasisInTransitionException {
    public EntityInTransitionException(String message) {
        super(message);
    }
}
