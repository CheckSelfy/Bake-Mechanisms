package io.github.checkselfy.bakemechanisms.profiling;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.bukkit.ServerTickManager;

/**
 * Runs per-tick farm work only on server ticks in which the game itself advanced.
 *
 * <p>Paper keeps running plugin schedulers while {@code /tick freeze} stops every world: blocks, entities and
 * the world's game time stand still, but scheduler tasks still run about 20 times a second. A profile is a rate
 * per game tick, so both profiling and stasis production must skip such ticks. Otherwise a farm in stasis keeps
 * producing for the whole freeze (the #12 benchmark credited about 1,100 frozen ticks of output within 61 game
 * ticks), and a profile measured across a freeze is diluted by ticks in which nothing could be produced.
 * {@code /tick step} ticks and {@code /tick sprint} ticks are game ticks and are counted.
 */
public final class GameTickGate implements Runnable {
    private final BooleanSupplier gameRuns;
    private final Runnable task;

    /** @param gameRuns whether the worlds tick during the current server tick */
    public GameTickGate(BooleanSupplier gameRuns, Runnable task) {
        this.gameRuns = Objects.requireNonNull(gameRuns, "gameRuns");
        this.task = Objects.requireNonNull(task, "task");
    }

    /**
     * Paper evaluates {@link ServerTickManager#isRunningNormally()} at the start of each server tick, before plugin
     * tasks and world ticks, so it tells whether the worlds tick in this same server tick.
     */
    public static GameTickGate of(ServerTickManager manager, Runnable task) {
        Objects.requireNonNull(manager, "manager");
        return new GameTickGate(manager::isRunningNormally, task);
    }

    @Override
    public void run() {
        if (gameRuns.getAsBoolean()) task.run();
    }
}
