package io.github.checkselfy.bakemechanisms.profiling;

import java.math.BigDecimal;
import java.math.MathContext;

/** How production rates are shown to players: items per server tick. Profiles store them per 20 ticks. */
public final class ProductionRate {
    private static final BigDecimal STORED_WINDOW_TICKS = BigDecimal.valueOf(20);
    private static final MathContext SHOWN = new MathContext(4);

    private ProductionRate() { }

    /** A stored rate (items per 20 ticks) as items per tick, to four significant digits, without exponent. */
    public static String perTick(double itemsPer20Ticks) {
        var perTick = BigDecimal.valueOf(itemsPer20Ticks).divide(STORED_WINDOW_TICKS, SHOWN).stripTrailingZeros();
        return perTick.signum() == 0 ? "0" : perTick.toPlainString();
    }
}
