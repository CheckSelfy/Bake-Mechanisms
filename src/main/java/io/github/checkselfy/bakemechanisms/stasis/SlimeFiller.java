package io.github.checkselfy.bakemechanisms.stasis;

import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import org.bukkit.Material;

/** Pure local-coordinate generator. Version 1 never uses air, entities, random numbers or ticked blocks. */
public final class SlimeFiller {
    private final long width,height,depth,faceWidth;
    private final FillerStyle style;

    public SlimeFiller(Cuboid region,FillerStyle style) {
        width=(long)region.max().x()-region.min().x()+1;
        height=(long)region.max().y()-region.min().y()+1;
        depth=(long)region.max().z()-region.min().z()+1;
        this.style=style.forRegion(region);
        faceWidth=switch(this.style.front()) { case EAST,WEST -> depth; default -> width; };
    }

    public Material material(long x,long y,long z) {
        if(x<0 || x>=width || y<0 || y>=height || z<0 || z>=depth)
            throw new IllegalArgumentException("Координаты за пределами заполнителя.");
        if(style.version()==0) return Material.BEDROCK;
        var palette=style.palette();
        long a=Math.min(x,width-1-x),b=Math.min(y,height-1-y),c=Math.min(z,depth-1-z);
        boolean boundary=a==0 || b==0 || c==0;
        long distance=switch(style.front()) {
            case NORTH -> z; case SOUTH -> depth-1-z; case EAST -> width-1-x; case WEST -> x;
            case AUTO -> throw new IllegalStateException("Unresolved filler front");
        };
        long u=switch(style.front()) { case EAST,WEST -> z; default -> x; };
        if(facePixel(u,y)) {
            if(distance==0) return palette.faceGlass();
            if(distance==1 && !boundary) return palette.face();
        }
        // Small volumes have no core; retain a readable, deterministic two-tone body.
        if(width<3 || height<3 || depth<3) return y*2>=height ? palette.shell() : palette.shade();
        long phase=style.pattern()==FillerStyle.Pattern.BANDS
                ? (a+b+c)/style.patchSize() : a/style.patchSize()+b/style.patchSize()+c/style.patchSize();
        if(!boundary && x*4>=width && x*4<width*3 && y*4>=height && y*4<height*3
                && z*4>=depth && z*4<depth*3)
            return phase%5==0 ? palette.accent() : palette.core();
        int edges=(a==0?1:0)+(b==0?1:0)+(c==0?1:0);
        return edges>=2 || phase%(style.pattern()==FillerStyle.Pattern.BANDS?6:4)==1 ? palette.shade() : palette.shell();
    }

    /** Integer squares and parity-aware gaps keep both eyes and the mouth exactly centred. */
    public boolean facePixel(long u,long y) {
        if(faceWidth<3 || height<3) return false;
        long eye=Math.max(1,Math.min(faceWidth/4,height/4));
        long gapX=Math.max(1,eye);
        if((faceWidth-gapX)%2!=0) gapX++;
        long left=(faceWidth-2*eye-gapX)/2, right=faceWidth-left-eye;
        long mouthHeight=Math.max(1,eye/2),gapY=Math.max(1,eye/2);
        if((height-eye-mouthHeight-gapY)%2!=0) gapY++;
        long bottom=(height-eye-mouthHeight-gapY)/2,eyeY=bottom+mouthHeight+gapY;
        long mouthWidth=eye;
        if((faceWidth-mouthWidth)%2!=0) mouthWidth++;
        long mouthX=(faceWidth-mouthWidth)/2;
        return y>=eyeY && y<eyeY+eye && ((u>=left && u<left+eye) || (u>=right && u<right+eye))
                || y>=bottom && y<bottom+mouthHeight && u>=mouthX && u<mouthX+mouthWidth;
    }
}
