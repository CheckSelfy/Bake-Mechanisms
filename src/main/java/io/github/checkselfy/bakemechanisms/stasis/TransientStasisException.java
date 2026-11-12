package io.github.checkselfy.bakemechanisms.stasis;

/**
 * The mechanism is briefly unstable: entering stasis can succeed a few ticks later without anyone intervening.
 * Raised before any snapshot is published or any world data is changed, so retrying is always safe.
 */
public abstract class TransientStasisException extends IllegalArgumentException {
    protected TransientStasisException(String message) {
        super(message);
    }

    /** How many further ticks one entry request may wait for this kind of instability before giving up. */
    public abstract int maxRetries();
}
