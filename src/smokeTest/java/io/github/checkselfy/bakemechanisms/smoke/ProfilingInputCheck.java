package io.github.checkselfy.bakemechanisms.smoke;

import io.github.checkselfy.bakemechanisms.marking.*;
import io.github.checkselfy.bakemechanisms.profiling.*;
import java.util.*;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.block.Container;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Hopper;
import org.bukkit.entity.Item;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Chicken;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/** Native hoppers, prefilled sources, returning chains and mixed item origins during a complete profile. */
final class ProfilingInputCheck extends BukkitRunnable {
    private final JavaPlugin plugin;
    private final World world;
    private final Consumer<Throwable> finish;
    private final UUID creator = UUID.randomUUID();
    private final FarmRegistry registry = new FarmRegistry(1000);
    private final PaperProfilingWorld backend;
    private final ProfilingService service;
    private final ProfilingProtection players;
    private final ProfilingTransportProtection transport;
    private final List<Item> unknown = new ArrayList<>();
    private FarmRegistry.MarkedFarm farm;
    private int tick;
    private boolean compatibility;
    private UUID woolFarm, eggFarm;
    private Sheep sheep;
    private Chicken chicken;

    static void run(JavaPlugin plugin, Consumer<Throwable> finish) {
        var check = new ProfilingInputCheck(plugin, finish);
        try { check.setup(); check.runTaskTimer(plugin, 1, 1); }
        catch (Throwable error) { check.cleanup(); finish.accept(error); }
    }

    private ProfilingInputCheck(JavaPlugin plugin, Consumer<Throwable> finish) {
        this.plugin = plugin; this.finish = finish;
        world = plugin.getServer().getWorlds().getFirst();
        backend = new PaperProfilingWorld(plugin, new PaperOutputResolver());
        service = new ProfilingService(registry, backend, ProfilingSettings.DEFAULT);
        players = new ProfilingProtection(service);
        transport = new ProfilingTransportProtection(service);
    }

    private void setup() throws Exception {
        for (int cx = 31; cx <= 33; cx++) for (int cz = -1; cz <= 1; cz++) world.getChunkAt(cx,cz).load();
        for (var entity : world.getNearbyEntities(new BoundingBox(509,298,-3,525,307,8))) entity.remove();
        for (int x=510;x<=523;x++) for(int y=300;y<=305;y++) for(int z=-2;z<=6;z++) {
            if (world.getBlockAt(x,y,z).getState(false) instanceof Container container) container.getInventory().clear();
            world.getBlockAt(x,y,z).setType(Material.AIR,false);
        }
        var region = new Cuboid(p(512,300,0),p(517,304,4));
        var output = world.getBlockAt(518,301,2); output.setType(Material.BARREL,false);
        hopper(517,301,2,BlockFace.EAST,true);
        hopper(518,300,2,BlockFace.SOUTH,false); // Drain exists before the run; opened near its end.
        world.getBlockAt(518,300,3).setType(Material.BARREL,false);
        farm = registry.register(creator,region,new PaperOutputResolver().resolve(output));
        plugin.getServer().getPluginManager().registerEvents(players,plugin);
        plugin.getServer().getPluginManager().registerEvents(transport,plugin);

        var level=world.getClass().getMethod("getHandle").invoke(world);
        var config=level.getClass().getMethod("paperConfig").invoke(level);
        var hopperConfig=config.getClass().getField("hopper").get(config);
        var disabled=hopperConfig.getClass().getField("disableMoveEvent");
        boolean wasDisabled=disabled.getBoolean(hopperConfig);
        try { disabled.setBoolean(hopperConfig,true); refuse("disable-move-event"); }
        finally { disabled.setBoolean(hopperConfig,wasDisabled); }

        contents(517,301,2).setItem(0,new ItemStack(Material.DIAMOND,64));
        refuse("внутренние");
        contents(517,301,2).clear();
        hopper(518,302,2,BlockFace.DOWN,true);
        contents(518,302,2).setItem(0,new ItemStack(Material.DIAMOND,64));
        refuse("Подающая");
        contents(518,302,2).clear();
        world.getBlockAt(518,302,2).setType(Material.AIR,false);
        var initial = world.dropItem(new Location(world,517.5,302.05,2.5),new ItemStack(Material.SUGAR_CANE,64));
        initial.setVelocity(new Vector());
        initial.setGravity(false); initial.setUnlimitedLifetime(true);
        unknown.add(initial); // Initial loose loot is allowed, but never trusted as fresh production.
        require(world.getPluginChunkTickets().getOrDefault(plugin,List.of()).isEmpty(),"Rejected startup leaked tickets");

        contents(518,301,2).setItem(0,new ItemStack(Material.DIAMOND,32)); // Baseline must not become production.
        service.start(farm.id(),creator);
        // Enable a returning outside chain after validation; these are real native transfer attempts.
        hopper(518,302,2,BlockFace.DOWN,true);
        hopper(519,302,2,BlockFace.WEST,true);
        hopper(520,302,2,BlockFace.WEST,true);
        contents(518,302,2).setItem(0,new ItemStack(Material.DIAMOND,64));
        contents(520,302,2).setItem(0,new ItemStack(Material.DIAMOND,64));
        for(var type:List.of(Material.DIAMOND,Material.SUGAR_CANE)) {
            var item=world.dropItem(new Location(world,520.5,302.05,2.5),new ItemStack(type,64));
            item.setVelocity(new Vector()); item.setGravity(false); item.setUnlimitedLifetime(true);
            item.teleport(new Location(world,517.5,302.05,2.5)); // Same UUID, like water/physics entering from outside.
            unknown.add(item);
        }
        plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),"tick sprint 3605");
    }

    @Override public void run() {
        try {
            tick++;
            if (compatibility) {
                service.tick();
                // Native mob drops have a pickup delay, followed by three hopper cooldowns for eggs.
                if (tick < 180) return;
                require(sheep.isSheared() && count(550,301,2,Material.BLUE_WOOL)>0,"Normal dispenser shearing/collection was blocked: sheared="
                        +sheep.isSheared()+", output="+contents(550,301,2)+", wool="+count(550,301,2,Material.BLUE_WOOL)
                        +", dispenser="+java.util.Arrays.toString(contents(548,302,2).getContents())
                        +", state="+registry.requireCreator(woolFarm,creator).state()
                        +", drops="+world.getNearbyEntities(new BoundingBox(542,299,-2,552,307,7)).stream()
                            .filter(entity->entity instanceof Item).map(entity->((Item)entity).getItemStack()+" at "+entity.getLocation()).toList());
                var tool=contents(548,302,2).getItem(0);
                require(tool!=null && ((org.bukkit.inventory.meta.Damageable)tool.getItemMeta()).getDamage()>0,"Shears were not used by native dispenser");
                require(count(582,301,2,Material.EGG)>0,"Natural egg production/collection was blocked: timer="
                        +chicken.getEggLayTime()+", state="+registry.requireCreator(eggFarm,creator).state()
                        +", output="+java.util.Arrays.toString(contents(582,301,2).getContents())
                        +", collectors="+List.of(contents(579,301,2),contents(580,301,2),contents(581,301,2)).stream()
                            .map(inventory->java.util.Arrays.toString(inventory.getContents())).toList()
                        +", drops="+world.getNearbyEntities(new BoundingBox(574,299,-2,584,307,7)).stream()
                            .filter(entity->entity instanceof Item).map(entity->((Item)entity).getItemStack()+" at "+entity.getLocation()).toList());
                service.cancel(woolFarm,creator); service.cancel(eggFarm,creator);
                require(service.isolations().isEmpty() && world.getPluginChunkTickets().getOrDefault(plugin,List.of()).isEmpty(),"Compatibility runs leaked protection/tickets");
                plugin.getLogger().info("PROFILING_NATURAL_INPUT_SMOKE_PASSED: native shears dispenser, wool and chicken egg collection");
                cancel(); cleanup(); finish.accept(null); return;
            }
            if(tick<=3540 && tick%20==1) {
                var item=world.dropItem(new Location(world,517.5,302.05,2.5),new ItemStack(Material.SUGAR_CANE));
                item.setVelocity(new Vector());
            }
            if(tick==3540) hopper(518,300,2,BlockFace.SOUTH,true);
            service.tick();
            if(tick<ProfilingSettings.DURATION_TICKS) return;
            var done=registry.requireCreator(farm.id(),creator);
            require(done.state()==FarmRegistry.State.READY,"Valid farm did not finish profiling: "+done.state());
            var rates=done.profile().ratesPer20Ticks();
            require(rates.keySet().equals(Set.of(Material.SUGAR_CANE)),"Injected material entered production profile: "+rates);
            require(rates.get(Material.SUGAR_CANE)>0 && rates.get(Material.SUGAR_CANE)<=1.05,"Injected cane inflated the measured rate: "+rates);
            require(count(518,302,2,Material.DIAMOND)==64,"Adjacent hopper injected items");
            require(count(520,302,2,Material.DIAMOND)==64 && contents(519,302,2).isEmpty(),"Outside chain entered the protected belt");
            for(var item:unknown) require(item.isValid() && item.getItemStack().getAmount()==64,"Unknown items were picked up or merged");
            require(count(518,300,3,Material.DIAMOND)>0,"Legitimate output draining was blocked");
            require(service.isolations().isEmpty(),"Finished run retained item provenance/protection");
            require(world.getPluginChunkTickets().getOrDefault(plugin,List.of()).isEmpty(),"Finished run leaked tickets");
            for(var item:unknown) item.remove(); unknown.clear();
            contents(517,301,2).clear();
            contents(518,302,2).clear();
            contents(519,302,2).clear();
            contents(520,302,2).clear();
            world.getBlockAt(518,302,2).setType(Material.AIR,false);
            world.getBlockAt(519,302,2).setType(Material.AIR,false);
            world.getBlockAt(520,302,2).setType(Material.AIR,false);
            registry.markNeedsProfiling(farm.id());
            service.start(farm.id(),creator); service.cancel(farm.id(),creator);
            require(service.isolations().isEmpty() && world.getPluginChunkTickets().getOrDefault(plugin,List.of()).isEmpty(),"Cancellation leaked protection/tickets");
            plugin.getLogger().info("PROFILING_INPUT_SMOKE_PASSED: live hopper event config, internal/adjacent prefill, returning chain, unknown pickups, mixed merges, output baseline/drain; profile="+rates);
            startCompatibility();
        } catch(Throwable error) { cancel(); cleanup(); finish.accept(error); }
    }

    private void startCompatibility() {
        for(int base:List.of(544,576)) {
            for(int cx=(base-2)>>4;cx<=(base+7)>>4;cx++) for(int cz=-1;cz<=1;cz++) world.getChunkAt(cx,cz).load();
            for(var entity:world.getNearbyEntities(new BoundingBox(base-2,299,-2,base+8,306,7))) entity.remove();
            for(int x=base-2;x<=base+7;x++) for(int y=300;y<=305;y++) for(int z=-2;z<=6;z++) {
                var block=world.getBlockAt(x,y,z);
                if(block.getState(false) instanceof Container container) container.getInventory().clear();
                block.setType(Material.AIR,false);
            }
            world.getBlockAt(base+6,301,2).setType(Material.BARREL,false);
            hopper(base+5,301,2,BlockFace.EAST,true);
            if(base==544) {
                var dispenser=world.getBlockAt(548,302,2);
                dispenser.setType(Material.DISPENSER,false);
                var data=(org.bukkit.block.data.Directional)dispenser.getBlockData(); data.setFacing(BlockFace.EAST); dispenser.setBlockData(data,false);
                contents(548,302,2).setItem(0,new ItemStack(Material.SHEARS));
                // A real one-cell shearing pen: wool must fall onto the collector, not fly onto the dispenser/output.
                for (int y=302;y<=303;y++) {
                    world.getBlockAt(549,y,1).setType(Material.GLASS,false);
                    world.getBlockAt(549,y,3).setType(Material.GLASS,false);
                    world.getBlockAt(550,y,2).setType(Material.GLASS,false);
                }
                world.getBlockAt(548,303,2).setType(Material.GLASS,false);
                world.getBlockAt(549,304,2).setType(Material.GLASS,false);
                sheep=world.spawn(new Location(world,549.5,302,2.5),Sheep.class);
                sheep.setAdult(); sheep.setColor(DyeColor.BLUE); sheep.setAI(false); sheep.setGravity(false); sheep.setPersistent(true);
            } else {
                hopper(579,301,2,BlockFace.EAST,true); hopper(580,301,2,BlockFace.EAST,true);
                chicken=world.spawn(new Location(world,579.5,302,2.5),Chicken.class);
                chicken.setVariant(Chicken.Variant.TEMPERATE); // Other variants lay BLUE_EGG/BROWN_EGG on this version.
                chicken.setAdult(); chicken.setGravity(false); chicken.setPersistent(true); chicken.setEggLayTime(1);
                for(int y=302;y<=303;y++) for(var cell:List.of(new int[]{578,2},new int[]{580,2},new int[]{579,1},new int[]{579,3}))
                    world.getBlockAt(cell[0],y,cell[1]).setType(Material.GLASS,false);
            }
            var registered=registry.register(creator,new Cuboid(p(base,300,0),p(base+5,304,4)),
                    new PaperOutputResolver().resolve(world.getBlockAt(base+6,301,2)));
            service.start(registered.id(),creator);
            if(base==544) woolFarm=registered.id(); else eggFarm=registered.id();
        }
        world.getBlockAt(548,302,1).setType(Material.REDSTONE_BLOCK,true);
        compatibility=true; tick=0;
    }

    private void refuse(String expected) {
        try { service.start(farm.id(),creator); throw new AssertionError("Unsafe initial sources accepted"); }
        catch(IllegalArgumentException error) { require(error.getMessage().contains(expected),"Unexpected rejection: "+error.getMessage()); }
        require(registry.requireCreator(farm.id(),creator).state()==FarmRegistry.State.NEEDS_PROFILING,"Rejected startup changed state");
    }

    private void hopper(int x,int y,int z,BlockFace face,boolean enabled) {
        var data=(Hopper) Material.HOPPER.createBlockData(); data.setFacing(face); data.setEnabled(enabled);
        var block=world.getBlockAt(x,y,z);
        if(block.getType()==Material.HOPPER) block.setBlockData(data,false);
        else { block.setType(Material.HOPPER,false); block.setBlockData(data,false); }
    }
    private org.bukkit.inventory.Inventory contents(int x,int y,int z) { return ((Container)world.getBlockAt(x,y,z).getState(false)).getInventory(); }
    private int count(int x,int y,int z,Material type) { int count=0; for(var item:contents(x,y,z).getContents()) if(item!=null && item.getType()==type) count+=item.getAmount(); return count; }
    private BlockPosition p(int x,int y,int z) { return new BlockPosition(world.getUID(),x,y,z); }
    private void cleanup() { service.cancelAll(); HandlerList.unregisterAll(players); HandlerList.unregisterAll(transport); for(var item:unknown) if(item.isValid()) item.remove(); }
    private static void require(boolean test,String message) { if(!test) throw new AssertionError(message); }
}
