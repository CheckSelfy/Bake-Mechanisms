package io.github.checkselfy.bakemechanisms.stasis;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Enters stasis on the first tick in which the mechanism is stable. A farm keeps running natively between
 * attempts: pistons finish their stroke, killed mobs finish dying and riders finish mounting. Each attempt
 * repeats every ownership, state and occupancy check of {@link StasisService#enter}. At most one attempt per farm
 * is waiting at a time. Server thread only.
 */
public final class StasisStabilizer {
    /** A killed mob needs 20 ticks to leave the world; several overlapping kills and strokes fit in 5 seconds. */
    public static final int MAX_WAIT_TICKS = 100;
    public static final int STATUS_EVERY_TICKS = 20;

    public interface Listener {
        /** Called on the first refused attempt and then every {@link #STATUS_EVERY_TICKS} ticks. */
        void waiting(int waitedTicks, String reason);
        void entered();
        void failed(IllegalArgumentException error);
    }

    private final StasisService stasis;
    private final Consumer<Runnable> nextTick;
    private final Set<UUID> pending = new HashSet<>();

    /** @param nextTick runs a task on the next server tick; {@code null} disables retries */
    public StasisStabilizer(StasisService stasis, Consumer<Runnable> nextTick) {
        this.stasis = stasis;
        this.nextTick = nextTick;
    }

    public boolean isPending(UUID farm) {
        return pending.contains(farm);
    }

    /** @return {@code false}, without another attempt, when this farm already waits for a stable tick */
    public boolean enter(UUID farm, UUID actor, Listener listener) {
        if (pending.contains(farm)) return false;
        attempt(farm, actor, listener, 0);
        return true;
    }

    private void attempt(UUID farm, UUID actor, Listener listener, int waited) {
        StasisInTransitionException changing;
        try {
            stasis.enter(farm, actor);
            pending.remove(farm);
            listener.entered();
            return;
        } catch (StasisInTransitionException transition) {
            changing = transition;
        } catch (RuntimeException error) {
            pending.remove(farm);
            if (error instanceof IllegalArgumentException refused) {
                listener.failed(refused);
                return;
            }
            throw error;
        }
        if (nextTick == null || waited >= MAX_WAIT_TICKS) {
            pending.remove(farm);
            listener.failed(waited == 0 ? changing : new StasisInTransitionException(changing.getMessage()
                    + " Механизм не стабилизировался за " + waited + " тиков; ферма осталась READY. Повторите стазис."));
            return;
        }
        pending.add(farm);
        if (waited % STATUS_EVERY_TICKS == 0) listener.waiting(waited, changing.getMessage());
        nextTick.accept(() -> attempt(farm, actor, listener, waited + 1));
    }
}
