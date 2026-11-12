package io.github.checkselfy.bakemechanisms.profiling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.ServerTickManager;
import org.junit.jupiter.api.Test;

class GameTickGateTest {
    @Test
    void frozenServerTicksAreNotCountedAndSteppedOrNormalTicksAre() {
        var runs = new AtomicBoolean(true);
        var ticks = new AtomicInteger();
        var gate = new GameTickGate(runs::get, ticks::incrementAndGet);
        for (int i = 0; i < 20; i++) gate.run();
        runs.set(false); // /tick freeze: the scheduler keeps calling the task, the worlds do not tick
        for (int i = 0; i < 1_000; i++) gate.run();
        runs.set(true);
        for (int i = 0; i < 41; i++) gate.run();
        assertEquals(61, ticks.get());
    }

    @Test
    void paperTickManagerDecidesWhetherTheGameRuns() {
        var manager = mock(ServerTickManager.class);
        var ticks = new AtomicInteger();
        var gate = GameTickGate.of(manager, ticks::incrementAndGet);
        when(manager.isRunningNormally()).thenReturn(false);
        gate.run();
        when(manager.isRunningNormally()).thenReturn(true);
        gate.run();
        assertEquals(1, ticks.get());
    }
}
