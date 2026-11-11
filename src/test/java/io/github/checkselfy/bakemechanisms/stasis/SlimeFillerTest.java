package io.github.checkselfy.bakemechanisms.stasis;

import static org.junit.jupiter.api.Assertions.*;
import io.github.checkselfy.bakemechanisms.marking.BlockPosition;
import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SlimeFillerTest {
    @Test void allRequiredSizesAreCompletelyFilledWithSafeMaterialsAndOnlyOneCentredFace() {
        for(var size:List.of(new int[]{3,3,3},new int[]{5,5,5},new int[]{10,10,10},new int[]{20,20,20},
                new int[]{15,5,10},new int[]{5,20,5},new int[]{30,5,3},new int[]{2,2,2},new int[]{4,7,6},new int[]{1,1,1})) {
            var region=region(size[0],size[1],size[2],0);
            for(var front:List.of(FillerStyle.Front.NORTH,FillerStyle.Front.SOUTH,FillerStyle.Front.EAST,FillerStyle.Front.WEST)) {
                var style=new FillerStyle(1,front,FillerStyle.Pattern.BANDS,3,FillerStyle.DEFAULT_PALETTE);
                var filler=new SlimeFiller(region,style);
                var materials=new HashSet<Material>();
                for(int x=0;x<size[0];x++) for(int y=0;y<size[1];y++) for(int z=0;z<size[2];z++) {
                    var type=filler.material(x,y,z); materials.add(type);
                    assertFalse(java.util.Set.of(Material.AIR,Material.CAVE_AIR,Material.VOID_AIR).contains(type)); assertNotEquals(Material.BEDROCK,type);
                    assertNotEquals(Material.SLIME_BLOCK,type); assertNotEquals(Material.HONEY_BLOCK,type);
                    if(x==0 || y==0 || z==0 || x==size[0]-1 || y==size[1]-1 || z==size[2]-1)
                        assertTrue(type.name().endsWith("_STAINED_GLASS"),"Opaque exterior at "+x+","+y+","+z);
                    if(type==Material.BLACK_STAINED_GLASS) assertTrue(switch(front) {
                        case NORTH -> z==0; case SOUTH -> z==size[2]-1; case EAST -> x==size[0]-1; case WEST -> x==0;
                        default -> false;
                    },"Face leaked onto another side");
                }
                long faceWidth=front==FillerStyle.Front.EAST || front==FillerStyle.Front.WEST ? size[2] : size[0];
                int facePixels=0;
                for(int u=0;u<faceWidth;u++) for(int y=0;y<size[1];y++) {
                    assertEquals(filler.facePixel(u,y),filler.facePixel(faceWidth-1-u,y),"Asymmetric face");
                    if(filler.facePixel(u,y)) facePixels++;
                }
                assertEquals(faceWidth>=3 && size[1]>=3,facePixels>0);
                if(facePixels>0) {
                    var pixels=new HashSet<String>();
                    int minY=Integer.MAX_VALUE,maxY=-1;
                    for(int u=0;u<faceWidth;u++) for(int y=0;y<size[1];y++) if(filler.facePixel(u,y)) {
                        pixels.add(u+","+y); minY=Math.min(minY,y); maxY=Math.max(maxY,y);
                    }
                    assertEquals(size[1]-1,minY+maxY,"Face not centred vertically");
                    int components=0;
                    while(!pixels.isEmpty()) {
                        components++; var pending=new java.util.ArrayDeque<String>(); pending.add(pixels.iterator().next());
                        while(!pending.isEmpty()) {
                            var pixel=pending.removeFirst(); if(!pixels.remove(pixel)) continue;
                            var parts=pixel.split(","); int u=Integer.parseInt(parts[0]),y=Integer.parseInt(parts[1]);
                            for(var neighbour:List.of((u-1)+","+y,(u+1)+","+y,u+","+(y-1),u+","+(y+1)))
                                if(pixels.contains(neighbour)) pending.add(neighbour);
                        }
                    }
                    assertEquals(3,components,"Eyes and mouth overlap or are missing");
                }
                if(size[0]>=3 && size[1]>=3 && size[2]>=3) assertTrue(materials.size()>=3);
                assertThrows(IllegalArgumentException.class,()->filler.material(-1,0,0));
                assertThrows(IllegalArgumentException.class,()->filler.material(size[0],0,0));
            }
        }
    }

    @Test void changingWorldOrPositionNeverChangesTheLocalPatternAndAutoFacesTheWiderSide() {
        var first=region(5,7,10,0); var shifted=region(5,7,10,-1234);
        assertEquals(FillerStyle.Front.EAST,FillerStyle.DEFAULT.forRegion(first).front());
        assertEquals(FillerStyle.Front.SOUTH,FillerStyle.DEFAULT.forRegion(region(10,7,5,0)).front());
        var a=new SlimeFiller(first,FillerStyle.DEFAULT); var b=new SlimeFiller(shifted,FillerStyle.DEFAULT);
        for(int x=0;x<5;x++) for(int y=0;y<7;y++) for(int z=0;z<10;z++) assertEquals(a.material(x,y,z),b.material(x,y,z));
    }

    @Test void paletteAndPatternAreConfigurableButUnsafeOrOpaqueShellsAreRejected() {
        var yaml=new YamlConfiguration(); yaml.set("pattern","PANELS"); yaml.set("front","WEST");
        yaml.set("patch-size",2); yaml.set("palette.shell","YELLOW_STAINED_GLASS");
        var style=FillerStyle.fromConfig(yaml);
        assertEquals(FillerStyle.Pattern.PANELS,style.pattern()); assertEquals(FillerStyle.Front.WEST,style.front());
        assertEquals(Material.YELLOW_STAINED_GLASS,style.palette().shell());
        for(var unsafe:List.of("SLIME_BLOCK","HONEY_BLOCK","SAND","CHEST","REDSTONE_BLOCK","AIR","GREEN_CONCRETE_POWDER","LEGACY_CONCRETE")) {
            yaml.set("palette.core",unsafe); assertThrows(IllegalArgumentException.class,()->FillerStyle.fromConfig(yaml));
        }
        yaml.set("palette.core","LIME_TERRACOTTA"); yaml.set("palette.shell","GREEN_CONCRETE");
        assertThrows(IllegalArgumentException.class,()->FillerStyle.fromConfig(yaml));
        assertThrows(IllegalArgumentException.class,()->new FillerStyle(2,FillerStyle.Front.NORTH,FillerStyle.Pattern.BANDS,3,FillerStyle.DEFAULT_PALETTE));
        assertEquals(Material.BEDROCK,new SlimeFiller(region(3,3,3,0),FillerStyle.LEGACY).material(1,1,1));
    }

    private static Cuboid region(int w,int h,int d,int offset) {
        var world=UUID.randomUUID();
        return new Cuboid(new BlockPosition(world,offset,64+offset,offset),new BlockPosition(world,offset+w-1,64+offset+h-1,offset+d-1));
    }
}
