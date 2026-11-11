package io.github.checkselfy.bakemechanisms.lab;

import io.github.checkselfy.bakemechanisms.marking.BlockPosition;
import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.marking.PaperOutputResolver;
import io.github.checkselfy.bakemechanisms.stasis.FillerStyle;
import io.github.checkselfy.bakemechanisms.stasis.PaperStasisWorld;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

/** Disposable lab only: a persistent visual gallery, separate from farms and production snapshots. */
public final class SlimeGallery extends JavaPlugin {
    private static final List<int[]> SIZES=List.of(new int[]{3,3,3},new int[]{5,5,5},new int[]{10,10,10},
            new int[]{20,20,20},new int[]{15,5,10},new int[]{5,20,5},new int[]{30,5,3},new int[]{2,2,2},new int[]{4,7,6});

    @Override public void onEnable() { Objects.requireNonNull(getCommand("slimegallery")).setExecutor(this); }

    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!(sender instanceof Player player)) { sender.sendMessage("Use /slimegallery [1..9] in the game."); return true; }
        try { if(args.length>1) throw new IllegalArgumentException("Use /slimegallery [1..9]"); visit(player,args.length==0?1:Integer.parseInt(args[0])); }
        catch(Exception error) { sender.sendMessage("Slime gallery: "+error.getMessage()); }
        return true;
    }

    private void visit(Player player,int index) throws Exception {
        var plugin=this;
        if(index<1 || index>SIZES.size()) throw new IllegalArgumentException("Use /slimegallery 1..9");
        var marker=plugin.getDataFolder().toPath().resolve("slime-gallery-v1.txt");
        var name="bake_slime_gallery";
        var level=plugin.getServer().getWorlds().getFirst().getWorldFolder().toPath();
        if(!Files.exists(marker) && (plugin.getServer().getWorld(name)!=null
                || Files.exists(level.resolve("dimensions/minecraft/"+name))))
            throw new IllegalStateException("Unrecognized gallery world; use a fresh disposable server directory");
        var world=new WorldCreator(name).seed(32).generateStructures(false).generator(new ChunkGenerator() {
            @Override public void generateNoise(WorldInfo info,Random random,int x,int z,ChunkData data) {
                for(int dx=0;dx<16;dx++) for(int dz=0;dz<16;dz++) data.setBlock(dx,63,dz,Material.SMOOTH_STONE);
            }
        }).createWorld();
        Objects.requireNonNull(world);
        world.setGameRule(GameRules.ADVANCE_TIME,false); world.setGameRule(GameRules.ADVANCE_WEATHER,false);
        world.setGameRule(GameRules.SPAWN_MOBS,false); world.setTime(6000);
        if(!Files.exists(marker)) {
            var adapter=new PaperStasisWorld(plugin,new PaperOutputResolver());
            for(int i=0;i<SIZES.size();i++) {
                var size=SIZES.get(i); int x=8+(i%3)*64,z=8+(i/3)*64;
                var region=new Cuboid(new BlockPosition(world.getUID(),x,64,z),
                        new BlockPosition(world.getUID(),x+size[0]-1,63+size[1],z+size[2]-1));
                adapter.fill(region,FillerStyle.DEFAULT);
            }
            Files.createDirectories(marker.getParent());
            Files.writeString(marker,"Slime filler visual gallery, algorithm 1; no active farm registrations.\n");
            getLogger().info("SLIME_GALLERY_CREATED sizes=9 world="+name);
        }
        int i=index-1; var size=SIZES.get(i); int x=8+(i%3)*64,z=8+(i/3)*64;
        player.setGameMode(GameMode.CREATIVE); player.setFlying(true);
        boolean east=size[2]>size[0];
        double distance=Math.max(10,Math.max(east?size[2]:size[0],size[1])*1.3);
        double cameraY=Math.max(64,64+size[1]/2.0-1.62);
        var location=east?new Location(world,x+size[0]+distance,cameraY,z+size[2]/2.0,90,0)
                :new Location(world,x+size[0]/2.0,cameraY,z+size[2]+distance,180,0);
        if(!player.teleport(location)) throw new IllegalStateException("Gallery teleport was cancelled");
        getLogger().info("SLIME_GALLERY_VISIT index="+index+" player="+player.getName());
        player.sendMessage("Slime "+index+": "+size[0]+"x"+size[1]+"x"+size[2]
                +". /slimegallery 1..9; /farmlab setup returns to the farms. Visual fixtures only; native tests verify protection/restore.");
    }
}
