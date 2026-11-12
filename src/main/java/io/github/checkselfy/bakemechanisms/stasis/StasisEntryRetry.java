package io.github.checkselfy.bakemechanisms.stasis;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Enters stasis and, while the mechanism is briefly unstable ({@link TransientStasisException}), tries again on
 * the next ticks within that instability's own budget. Server thread only.
 */
public final class StasisEntryRetry {
    /** What the requester learns about one entry request. */
    public interface Listener {
        /**
         * The attempt found the mechanism unstable and another attempt is scheduled for the next tick.
         *
         * @param retry number of the scheduled retry, from 1
         * @param retriesLeft further retries this kind of instability allows after the scheduled one
         */
        void waiting(TransientStasisException reason, int retry, int retriesLeft);

        void entered();

        /** The entry was refused, or the mechanism did not settle within its budget. */
        void failed(IllegalArgumentException error);
    }

    private final StasisService stasis;
    private final Consumer<Runnable> nextTick;
    private final Set<UUID> pending = new HashSet<>();

    /** @param nextTick runs a task on the next server tick; {@code null} disables retrying */
    public StasisEntryRetry(StasisService stasis, Consumer<Runnable> nextTick) {
        this.stasis = Objects.requireNonNull(stasis, "stasis");
        this.nextTick = nextTick;
    }

    /** Whether a retry of this farm's entry is already scheduled; a second request must not start another. */
    public boolean pending(UUID farmId) {
        return pending.contains(farmId);
    }

    public void enter(UUID farmId, UUID actorId, Listener listener) {
        attempt(farmId, actorId, Objects.requireNonNull(listener, "listener"), 0);
    }

    private void attempt(UUID farmId, UUID actorId, Listener listener, int retries) {
        try {
            stasis.enter(farmId, actorId);
            pending.remove(farmId);
            listener.entered();
        } catch (TransientStasisException unstable) {
            if (nextTick == null || retries >= unstable.maxRetries()) {
                pending.remove(farmId);
                listener.failed(unstable);
                return;
            }
            pending.add(farmId);
            listener.waiting(unstable, retries + 1, unstable.maxRetries() - retries - 1);
            nextTick.accept(() -> attempt(farmId, actorId, listener, retries + 1));
        } catch (IllegalArgumentException error) {
            pending.remove(farmId);
            listener.failed(error);
        }
    }
}
