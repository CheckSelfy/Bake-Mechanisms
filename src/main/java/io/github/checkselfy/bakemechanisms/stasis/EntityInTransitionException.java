package io.github.checkselfy.bakemechanisms.stasis;

/**
 * An entity of the mechanism is between two stable states: it is dying or being removed, being mounted or
 * dismounted, not yet added to the world, or its chunk's entities are still loading. Such a state ends by itself
 * within a death animation (20 ticks) or a few ticks, so stasis retries instead of asking the operator to
 * freeze the game, kill mobs or dismount riders.
 */
public final class EntityInTransitionException extends TransientStasisException {
    /** Five seconds: several death animations in a busy mob farm. */
    public static final int MAX_RETRIES = 100;

    public EntityInTransitionException(String message) {
        super(message);
    }

    @Override public int maxRetries() {
        return MAX_RETRIES;
    }
}
