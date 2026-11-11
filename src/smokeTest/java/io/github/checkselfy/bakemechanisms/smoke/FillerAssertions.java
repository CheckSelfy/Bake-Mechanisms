package io.github.checkselfy.bakemechanisms.smoke;

import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.stasis.FillerStyle;
import io.github.checkselfy.bakemechanisms.stasis.SlimeFiller;
import org.bukkit.block.Block;

final class FillerAssertions {
    private FillerAssertions() {}
    static boolean matches(Block block,Cuboid region) {
        // These legacy fixtures all place their only output against the east face.
        var style=new FillerStyle(1,FillerStyle.Front.EAST,FillerStyle.Pattern.BANDS,3,FillerStyle.DEFAULT_PALETTE);
        return block.getType()==new SlimeFiller(region,style).material((long)block.getX()-region.min().x(),
                (long)block.getY()-region.min().y(),(long)block.getZ()-region.min().z());
    }
}
