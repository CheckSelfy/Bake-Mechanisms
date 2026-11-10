package io.github.checkselfy.bakemechanisms.stasis;

import io.github.checkselfy.bakemechanisms.marking.PaperOutputResolver;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.world.StructureGrowEvent;

/** Protects physical blocks; extraction and insertion in the separate output remain allowed. */
public final class StasisProtection implements Listener {
    private final StasisService service;

    public StasisProtection(StasisService service) { this.service = service; }

    private boolean protectedBlock(Block block) {
        var position = PaperOutputResolver.position(block);
        return service.activeFarms().stream().anyMatch(farm -> farm.mechanism().contains(position)
                || farm.output().blocks().contains(position));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (protectedBlock(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (protectedBlock(event.getBlockPlaced()) || mergesWithOutput(event.getBlockPlaced())) event.setCancelled(true);
    }

    private boolean mergesWithOutput(Block block) {
        if (block.getType() != Material.CHEST) return false;
        var p = PaperOutputResolver.position(block);
        return service.activeFarms().stream()
                .filter(farm -> farm.output().type() != io.github.checkselfy.bakemechanisms.marking.OutputContainer.Type.BARREL)
                .flatMap(farm -> farm.output().blocks().stream())
                .anyMatch(o -> o.worldId().equals(p.worldId()) && o.y() == p.y()
                        && Math.abs((long) o.x() - p.x()) + Math.abs((long) o.z() - p.z()) == 1);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMultiPlace(BlockMultiPlaceEvent event) {
        if (event.getReplacedBlockStates().stream().anyMatch(state -> protectedBlock(state.getBlock()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) { protectExplosion(event.blockList()); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) { protectExplosion(event.blockList()); }

    private void protectExplosion(List<Block> blocks) { blocks.removeIf(this::protectedBlock); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (protectedBlock(event.getBlock()) || event.getBlocks().stream().anyMatch(block -> protectedBlock(block)
                || protectedBlock(block.getRelative(event.getDirection())))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (protectedBlock(event.getBlock()) || event.getBlocks().stream().anyMatch(block -> protectedBlock(block)
                || protectedBlock(block.getRelative(event.getDirection()))
                || protectedBlock(block.getRelative(event.getDirection().getOppositeFace())))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFluid(BlockFromToEvent event) {
        if (protectedBlock(event.getBlock()) || protectedBlock(event.getToBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent event) {
        if (protectedBlock(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (protectedBlock(event.getBlock()) || protectedBlock(event.getBlockClicked())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (protectedBlock(event.getBlock()) || protectedBlock(event.getBlockClicked())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) { if (protectedBlock(event.getBlock())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) { if (protectedBlock(event.getBlock())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) { if (protectedBlock(event.getBlock())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) { if (protectedBlock(event.getBlock())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) { if (protectedBlock(event.getBlock())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSponge(SpongeAbsorbEvent event) {
        event.getBlocks().removeIf(state -> protectedBlock(state.getBlock()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        if (event.getBlocks().stream().anyMatch(state -> protectedBlock(state.getBlock()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        if (event.getBlocks().stream().anyMatch(state -> protectedBlock(state.getBlock()))) event.setCancelled(true);
    }
}
