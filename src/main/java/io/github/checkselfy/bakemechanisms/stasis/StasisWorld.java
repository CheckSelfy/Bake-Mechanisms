package io.github.checkselfy.bakemechanisms.stasis;

import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.MarkedFarm;

/** Synchronous world operations. Capture must validate everything before changing any block. */
public interface StasisWorld {
    /** Rejects known identity conflicts before production is paused or rollback may remove entities. */
    default void validateRestore(MarkedFarm farm, byte[] data) {}
    boolean hasPlayerInside(Cuboid mechanism);
    byte[] capture(MarkedFarm farm);
    /** Removes only members recorded in the saved snapshot, including passengers outside the cuboid. */
    void removeEntities(MarkedFarm farm, byte[] data);
    default void fill(Cuboid mechanism) { fill(mechanism,FillerStyle.DEFAULT); }
    /** Uses the persisted appearance during both initial replacement and rollback. */
    void fill(Cuboid mechanism,FillerStyle style);
    void restore(MarkedFarm farm, byte[] data);
}
