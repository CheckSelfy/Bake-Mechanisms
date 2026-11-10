package io.github.checkselfy.bakemechanisms.stasis;

import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.MarkedFarm;

/** Synchronous world operations. Capture must validate everything before changing any block. */
public interface StasisWorld {
    boolean hasPlayerInside(Cuboid mechanism);
    byte[] capture(MarkedFarm farm);
    /** Removes only members recorded in the saved snapshot, including passengers outside the cuboid. */
    void removeEntities(MarkedFarm farm, byte[] data);
    void fill(Cuboid mechanism);
    void restore(MarkedFarm farm, byte[] data);
}
