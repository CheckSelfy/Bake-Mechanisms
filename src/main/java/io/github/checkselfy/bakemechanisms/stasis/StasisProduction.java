package io.github.checkselfy.bakemechanisms.stasis;

import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.MarkedFarm;
import java.util.UUID;

/** Item production that replaces the original mechanism while it is in stasis. Server thread only. */
public interface StasisProduction {
    StasisProduction NONE = new StasisProduction() {
        @Override public void start(MarkedFarm farm) { }
        @Override public void pause(UUID farmId) { }
        @Override public void resume(UUID farmId) { }
        @Override public void finish(UUID farmId) { }
    };

    /** The filler stands in for the mechanism: produce from the farm's profile, continuing any saved progress. */
    void start(MarkedFarm farm);

    /** The original is about to be placed: production must not run alongside it. */
    void pause(UUID farmId);

    /** Restore failed and the filler is back. */
    void resume(UUID farmId);

    /** The original is back: stop producing; only the leftover buffer is still delivered. */
    void finish(UUID farmId);
}
