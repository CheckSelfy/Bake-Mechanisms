package io.github.checkselfy.bakemechanisms.smoke;

import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.stasis.FillerStyle;
import io.github.checkselfy.bakemechanisms.stasis.SlimeFiller;
import org.bukkit.block.Block;

final class FillerAssertions {
    private FillerAssertions() {}
    static boolean matches(Block block,Cuboid region) {
        return block.getType()==new SlimeFiller(region,FillerStyle.DEFAULT).material((long)block.getX()-region.min().x(),
                (long)block.getY()-region.min().y(),(long)block.getZ()-region.min().z());
    }
}
