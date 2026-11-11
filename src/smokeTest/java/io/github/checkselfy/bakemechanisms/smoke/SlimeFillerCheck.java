package io.github.checkselfy.bakemechanisms.smoke;

import io.github.checkselfy.bakemechanisms.marking.*;
import io.github.checkselfy.bakemechanisms.profiling.ProductionProfile;
import io.github.checkselfy.bakemechanisms.stasis.*;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Actual Paper geometry, physics, durable palette recovery and failed-restore rollback, for all requested sizes. */
final class SlimeFillerCheck implements org.bukkit.event.Listener {
    private final JavaPlugin plugin;
    private final World world;
    private final PaperStasisWorld adapter;
    private final List<Case> cases=new ArrayList<>();
    private final List<StasisProtection> guards=new ArrayList<>();
    private org.bukkit.block.Block poweredPiston;
    private int cancelledPistonAttempts;
    private boolean powerApplied;
    private org.bukkit.scheduler.BukkitTask powerTask;
    private final Map<io.github.checkselfy.bakemechanisms.profiling.ChunkPosition,Boolean> forcedChunks=new LinkedHashMap<>();
    private record Case(FarmRegistry.MarkedFarm farm,UUID creator,FileSnapshotStore store,FillerStyle style,
                        Map<BlockPosition,String> original) {}

    private SlimeFillerCheck(JavaPlugin plugin) {
        this.plugin=plugin; world=plugin.getServer().getWorlds().getFirst();
        adapter=new PaperStasisWorld(plugin,new PaperOutputResolver());
    }

    static void run(JavaPlugin plugin,Consumer<Throwable> finish) {
        var test=new SlimeFillerCheck(plugin);
        try {
            test.prepare();
            plugin.getServer().getScheduler().runTaskLater(plugin,()->{
                try { test.checkAndRestore(); test.cleanup(); finish.accept(null); }
                catch(Throwable error) { test.cleanup(); finish.accept(error); }
            },50);
        } catch(Throwable error) { test.cleanup(); finish.accept(error); }
    }

    private void prepare() throws Exception {
        world.getBlockAt(798,261,65).setType(Material.AIR,true);
        world.getBlockAt(799,261,65).setType(Material.AIR,true);
        var sizes=List.of(new int[]{3,3,3},new int[]{5,5,5},new int[]{10,10,10},new int[]{20,20,20},
                new int[]{15,5,10},new int[]{5,20,5},new int[]{30,5,3},new int[]{2,2,2},new int[]{4,7,6});
        var meshes=new ArrayList<Map<String,Object>>();
        for(int index=0;index<sizes.size();index++) {
            var size=sizes.get(index); int x=800+(index%3)*64,z=64+(index/3)*64;
            var region=new Cuboid(p(x,260,z),p(x+size[0]-1,259+size[1],z+size[2]-1));
            var original=new LinkedHashMap<BlockPosition,String>();
            each(region,pos->{
                var block=world.getBlockAt(pos.x(),pos.y(),pos.z());
                if(block.getState(false) instanceof Container container) container.getInventory().clear();
                block.setType(Material.STONE,false);
            });
            world.getBlockAt(x,260,z).setType(Material.BARREL,false);
            ((Container)world.getBlockAt(x,260,z).getState(false)).getInventory().addItem(new ItemStack(Material.EMERALD,17));
            each(region,pos->original.put(pos,world.getBlockAt(pos.x(),pos.y(),pos.z()).getBlockData().getAsString()));
            var sentinel=world.getBlockAt(x-1,260,z); sentinel.setType(Material.GOLD_BLOCK,false);
            var output=world.getBlockAt(region.max().x()+1,260,z); output.setType(Material.BARREL,false);
            ((Container)output.getState(false)).getInventory().clear();
            ((Container)output.getState(false)).getInventory().addItem(new ItemStack(Material.DIAMOND,7));
            var registry=new FarmRegistry(FarmRegistry.DEFAULT_MAX_VOLUME);
            var creator=UUID.randomUUID(); var farm=registry.register(creator,region,new PaperOutputResolver().resolve(output));
            registry.markReady(farm.id(),new ProductionProfile(Map.of(Material.SUGAR_CANE,1.0),3600));
            var palette=index==8?new FillerStyle.Palette(Material.LIME_STAINED_GLASS,Material.GREEN_STAINED_GLASS,
                    Material.GREEN_TERRACOTTA,Material.LIME_CONCRETE,Material.BLACK_TERRACOTTA,Material.BLACK_STAINED_GLASS)
                    :FillerStyle.DEFAULT_PALETTE;
            var style=new FillerStyle(1,index%2==0?FillerStyle.Front.AUTO:FillerStyle.Front.WEST,
                    index%2==0?FillerStyle.Pattern.BANDS:FillerStyle.Pattern.PANELS,3,palette).forRegion(region);
            var store=new FileSnapshotStore(plugin.getDataFolder().toPath().resolve("slime-"+UUID.randomUUID()),"26.3");
            var service=new StasisService(registry,adapter,store,StasisProduction.NONE,style);
            guard(service); service.enter(farm.id(),creator);
            var test=new Case(farm,creator,store,style,original); cases.add(test);
            assertFiller(test); assertOutput(test);
            require(sentinel.getType()==Material.GOLD_BLOCK,"Filler wrote outside its cuboid");
            require(world.getNearbyEntities(io.github.checkselfy.bakemechanisms.profiling.PaperProfilingWorld.box(region)).isEmpty(),"Filler created entities/drops");
            var voxels=new ArrayList<List<Object>>();
            each(region,pos->voxels.add(List.of(pos.x()-x,pos.y()-260,pos.z()-z,world.getBlockAt(pos.x(),pos.y(),pos.z()).getType().name())));
            meshes.add(Map.of("size",size,"front",style.front().name(),"pattern",style.pattern().name(),"voxels",voxels));
        }
        // Export actual server blocks, not a second implementation of the pattern, for visual inspection.
        Files.writeString(plugin.getDataFolder().toPath().resolve("slime-gallery.json"),new com.google.gson.Gson().toJson(meshes));
        var small=cases.getFirst().farm().mechanism();
        world.createExplosion(small.min().x()+1.5,small.min().y()+1.5,small.min().z()-1.5,4.0f,false,true);
        var piston=world.getBlockAt(small.min().x()-1,small.min().y()+1,small.min().z()+1);
        poweredPiston=piston;
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        // Loaded chunks alone do not process piston block events on a playerless server.
        for(int cx=49;cx<=50;cx++) {
            forcedChunks.put(new io.github.checkselfy.bakemechanisms.profiling.ChunkPosition(world.getUID(),cx,4),world.isChunkForceLoaded(cx,4));
            world.setChunkForceLoaded(cx,4,true);
            world.addPluginChunkTicket(cx,4,plugin);
        }
        piston.setBlockData(plugin.getServer().createBlockData("minecraft:piston[facing=east,extended=false]"),false);
        powerTask=new org.bukkit.scheduler.BukkitRunnable() {
            int attempts;
            @Override public void run() {
                if(cancelledPistonAttempts>0 || attempts++>=20) { cancel(); return; }
                var power=world.getBlockAt(piston.getX()-1,piston.getY(),piston.getZ());
                power.setType(Material.AIR,true); power.setType(Material.REDSTONE_BLOCK,true);
                powerApplied=true;
            }
        }.runTaskTimer(plugin,4,2);
    }

    private void checkAndRestore() throws Exception {
        for(var test:cases) { assertFiller(test); assertOutput(test); }
        var small=cases.getFirst().farm().mechanism();
        require(world.getBlockAt(small.min().x()-1,small.min().y()+1,small.min().z()+1).getBlockData()
                instanceof org.bukkit.block.data.type.Piston piston && !piston.isExtended(),"External piston moved the filler");
        require(cancelledPistonAttempts>0,"Fixture never attempted a real piston extension: "+poweredPiston.getBlockData()
                +", callback="+powerApplied+", source="+world.getBlockAt(798,261,65).getType()
                +", power="+poweredPiston.isBlockPowered()+", line="+java.util.stream.IntStream.rangeClosed(800,804)
                    .mapToObj(x->world.getBlockAt(x,261,65).getType()).toList());
        world.getBlockAt(small.min().x()-2,small.min().y()+1,small.min().z()+1).setType(Material.AIR,true);
        world.getBlockAt(small.min().x()-1,small.min().y()+1,small.min().z()+1).setType(Material.AIR,false);
        for(var test:cases) {
            require(test.store().loadActive().getFirst().filler().equals(test.style()),"Saved appearance changed on reload");
            var failing=new StasisWorld() {
                public boolean hasPlayerInside(Cuboid box) { return adapter.hasPlayerInside(box); }
                public byte[] capture(FarmRegistry.MarkedFarm farm) { return adapter.capture(farm); }
                public void removeEntities(FarmRegistry.MarkedFarm farm,byte[] data) { adapter.removeEntities(farm,data); }
                public void fill(Cuboid box,FillerStyle style) { adapter.fill(box,style); }
                public void restore(FarmRegistry.MarkedFarm farm,byte[] data) {
                    world.getBlockAt(farm.mechanism().min().x(),farm.mechanism().min().y(),farm.mechanism().min().z()).setType(Material.AIR,false);
                    throw new IllegalStateException("Injected partial restore failure");
                }
            };
            var changed=new FillerStyle(1,FillerStyle.Front.NORTH,FillerStyle.Pattern.PANELS,1,FillerStyle.DEFAULT_PALETTE);
            var failed=new StasisService(new FarmRegistry(1),failing,test.store(),StasisProduction.NONE,changed);
            failed.recover();
            try { failed.restore(test.farm().id(),test.creator()); throw new AssertionError("Expected restore failure"); }
            catch(IllegalArgumentException expected) { require(expected.getMessage().contains("Снимок"),"Wrong restore failure"); }
            assertFiller(test); assertOutput(test);
            var recovered=new StasisService(new FarmRegistry(1),adapter,test.store(),StasisProduction.NONE,changed);
            recovered.recover(); recovered.restore(test.farm().id(),test.creator());
            each(test.farm().mechanism(),pos->require(world.getBlockAt(pos.x(),pos.y(),pos.z()).getBlockData().getAsString()
                    .equals(test.original().get(pos)),"Original block changed after restore at "+pos));
            var min=test.farm().mechanism().min();
            require(((Container)world.getBlockAt(min.x(),min.y(),min.z()).getState(false)).getInventory().containsAtLeast(new ItemStack(Material.EMERALD),17),"Internal inventory lost");
            assertOutput(test); require(test.store().loadActive().isEmpty(),"Successful restore retained active manifest");
        }
        plugin.getLogger().info("SLIME_FILLER_SMOKE_PASSED: 9 sizes, full geometry, safe glass surface, piston/explosion protection, output preservation, persisted style and rollback/restore");
    }

    private void assertFiller(Case test) {
        var region=test.farm().mechanism(); var generator=new SlimeFiller(region,test.style());
        each(region,pos->require(world.getBlockAt(pos.x(),pos.y(),pos.z()).getType()==generator.material(
                pos.x()-region.min().x(),pos.y()-region.min().y(),pos.z()-region.min().z()),"Incorrect filler at "+pos));
    }
    private void assertOutput(Case test) {
        var p=test.farm().outputs().getFirst().blocks().iterator().next();
        require(world.getBlockAt(p.x(),p.y(),p.z()).getState(false) instanceof Container container
                && container.getInventory().containsAtLeast(new ItemStack(Material.DIAMOND),7),"Output changed");
    }
    private void guard(StasisService service) { var guard=new StasisProtection(service); guards.add(guard); plugin.getServer().getPluginManager().registerEvents(guard,plugin); }
    @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.MONITOR)
    public void onPiston(org.bukkit.event.block.BlockPistonExtendEvent event) {
        if(event.getBlock().equals(poweredPiston) && event.isCancelled()) cancelledPistonAttempts++;
    }
    private void cleanup() {
        guards.forEach(HandlerList::unregisterAll); HandlerList.unregisterAll(this);
        if(powerTask!=null) powerTask.cancel();
        forcedChunks.forEach((chunk,wasForced)->{
            world.setChunkForceLoaded(chunk.x(),chunk.z(),wasForced);
            world.removePluginChunkTicket(chunk.x(),chunk.z(),plugin);
        });
    }
    private BlockPosition p(int x,int y,int z) { return new BlockPosition(world.getUID(),x,y,z); }
    private static void each(Cuboid box,Consumer<BlockPosition> action) {
        for(int x=box.min().x();x<=box.max().x();x++) for(int y=box.min().y();y<=box.max().y();y++) for(int z=box.min().z();z<=box.max().z();z++)
            action.accept(new BlockPosition(box.min().worldId(),x,y,z));
    }
    private static void require(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
