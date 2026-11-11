package io.github.checkselfy.bakemechanisms.stasis;

import static org.junit.jupiter.api.Assertions.*;
import io.github.checkselfy.bakemechanisms.marking.*;
import io.github.checkselfy.bakemechanisms.profiling.ProductionProfile;
import java.util.*;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class FillerFrontTest {
    private final UUID world=UUID.randomUUID();
    private final Cuboid region=new Cuboid(p(0,64,0),p(4,68,4));

    @Test void majorityOfOutputsWinsOnAnySideRegardlessOfRegionProportions() {
        for(var side:List.of(FillerStyle.Front.NORTH,FillerStyle.Front.SOUTH,FillerStyle.Front.EAST,FillerStyle.Front.WEST)) {
            var positions=switch(side) {
                case NORTH -> List.of(p(1,64,-1),p(3,64,-1));
                case SOUTH -> List.of(p(1,64,5),p(3,64,5));
                case EAST -> List.of(p(5,64,1),p(5,64,3));
                case WEST -> List.of(p(-1,64,1),p(-1,64,3));
                default -> throw new AssertionError();
            };
            var outputs=new ArrayList<OutputContainer>();
            positions.forEach(point->outputs.add(output(point)));
            outputs.add(output(side==FillerStyle.Front.EAST?p(1,64,-1):p(5,64,2)));
            assertEquals(side,FillerStyle.DEFAULT.forFarm(farm(outputs)).front());
            Collections.reverse(outputs);
            assertEquals(side,FillerStyle.DEFAULT.forFarm(farm(outputs)).front());
        }
    }

    @Test void doubleChestHasOneVoteAndTiesAreIndependentOfSelectionOrder() {
        var chest=new OutputContainer(OutputContainer.Type.DOUBLE_CHEST,Set.of(p(5,64,1),p(5,64,2)));
        var north=output(p(1,64,-1));
        assertEquals(FillerStyle.Front.NORTH,FillerStyle.DEFAULT.forFarm(farm(List.of(chest,north))).front());
        assertEquals(FillerStyle.Front.NORTH,FillerStyle.DEFAULT.forFarm(farm(List.of(north,chest))).front());
        assertEquals(FillerStyle.Front.SOUTH,FillerStyle.DEFAULT.forFarm(farm(List.of(output(p(1,64,5)),north))).front());
    }

    @Test void cornerAndVerticalOutputsUseDeterministicFallbacks() {
        assertEquals(FillerStyle.Front.SOUTH,FillerStyle.DEFAULT.forFarm(farm(List.of(output(p(5,64,5))))).front());
        assertEquals(FillerStyle.Front.SOUTH,FillerStyle.DEFAULT.forFarm(farm(List.of(output(p(2,69,2))))).front());
        var chest=new OutputContainer(OutputContainer.Type.DOUBLE_CHEST,Set.of(p(5,64,4),p(5,64,5)));
        assertEquals(FillerStyle.Front.EAST,FillerStyle.DEFAULT.forFarm(farm(List.of(chest))).front());
    }

    @Test void savedSnapshotResolvesOutputSideOnceAndExplicitSettingsRemainAuthoritative() {
        var farm=farm(List.of(output(p(2,64,-1))));
        var snapshot=new SavedSnapshot(farm,new byte[]{1},FillerStyle.DEFAULT);
        assertEquals(FillerStyle.Front.NORTH,snapshot.filler().front());
        var different=farm(List.of(output(p(5,64,2))));
        assertEquals(FillerStyle.Front.NORTH,snapshot.filler().forFarm(different).front());
        var explicit=new FillerStyle(1,FillerStyle.Front.WEST,FillerStyle.Pattern.BANDS,3,FillerStyle.DEFAULT_PALETTE);
        assertEquals(FillerStyle.Front.WEST,explicit.forFarm(farm).front());
    }

    private BlockPosition p(int x,int y,int z) { return new BlockPosition(world,x,y,z); }
    private OutputContainer output(BlockPosition position) { return new OutputContainer(OutputContainer.Type.BARREL,Set.of(position)); }
    private FarmRegistry.MarkedFarm farm(List<OutputContainer> outputs) {
        return new FarmRegistry.MarkedFarm(UUID.randomUUID(),UUID.randomUUID(),null,region,outputs,FarmRegistry.State.STASIS,
                new ProductionProfile(outputs.stream().map(output->Map.of(Material.SUGAR_CANE,1.0)).toList(),3600));
    }
}
