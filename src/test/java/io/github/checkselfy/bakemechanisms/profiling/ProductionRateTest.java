package io.github.checkselfy.bakemechanisms.profiling;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ProductionRateTest {
    @Test
    void storedRatePer20TicksIsShownPerTickWithFourSignificantDigits() {
        assertEquals("0.05", ProductionRate.perTick(1.0));
        assertEquals("0.001944", ProductionRate.perTick(0.03888888888888889));
        assertEquals("0.0002778", ProductionRate.perTick(0.005555555555555556));
        assertEquals("3", ProductionRate.perTick(60.0));
        assertEquals("0", ProductionRate.perTick(0.0));
    }
}
