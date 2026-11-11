package io.github.checkselfy.bakemechanisms.profiling;

import io.github.checkselfy.bakemechanisms.marking.BlockPosition;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry;
import io.github.checkselfy.bakemechanisms.marking.PaperOutputResolver;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.BoundingBox;

/**
 * Isolates profiled farms from players: no entry, no changes to the mechanism or output.
 * All handlers run on the server thread. Known gaps: vehicles ridden into the cuboid,
 * players logging in or respawning inside it, and indirect effects (fluids, pistons, TNT lit outside).
 */
public final class ProfilingProtection implements Listener {
    private static final Component DENIED = Component.text("Ферма профилируется: область защищена.");
    private final ProfilingService service;

    public ProfilingProtection(ProfilingService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        var player = event.getPlayer();
        if (entersMechanism(player, event.getTo()) && !entersMechanism(player, event.getFrom())) {
            deny(event, player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (entersMechanism(event.getPlayer(), event.getTo())) deny(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isProtected(event.getBlock())) deny(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        var placed = event.getBlockPlaced();
        if (isProtected(placed) || touchesOutput(placed)) deny(event, event.getPlayer());
    }

    /** A hopper or chest placed against the output could drain it or turn it into another container. */
    private boolean touchesOutput(Block block) {
        var p = PaperOutputResolver.position(block);
        return service.activeFarms().stream().flatMap(FarmRegistry.MarkedFarm::outputBlocks)
                .anyMatch(o -> o.worldId().equals(p.worldId()) && Math.abs((long) o.x() - p.x())
                        + Math.abs((long) o.y() - p.y()) + Math.abs((long) o.z() - p.z()) == 1);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onVehicleDamage(VehicleDamageEvent event) {
        var player = responsiblePlayer(event.getAttacker());
        if (player != null && insideMechanism(event.getVehicle())) deny(event, player);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        var player = responsiblePlayer(event.getAttacker());
        if (player != null && insideMechanism(event.getVehicle())) deny(event, player);
    }

    /** Covers levers, buttons, internal containers, pressure plates and opening the output. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        var block = event.getClickedBlock();
        if (block != null && isProtected(block)) deny(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        if (isProtected(event.getBlock()) || isProtected(event.getBlockClicked())) deny(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucket(PlayerBucketFillEvent event) {
        if (isProtected(event.getBlock()) || isProtected(event.getBlockClicked())) deny(event, event.getPlayer());
    }

    /** Also receives {@code PlayerInteractAtEntityEvent}, which shares this handler list. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (insideMechanism(event.getRightClicked())) deny(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (insideMechanism(event.getRightClicked())) deny(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        var player = responsiblePlayer(event.getDamager());
        if (player != null && insideMechanism(event.getEntity())) deny(event, player);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        var player = responsiblePlayer(event.getRemover());
        if (player != null && insideMechanism(event.getEntity())) deny(event, player);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        var holder = event.getInventory().getHolder(false);
        boolean output = holder instanceof DoubleChest chest
                ? isOutput(chest.getLeftSide(false)) || isOutput(chest.getRightSide(false))
                : isOutput(holder);
        if (output) deny(event, event.getPlayer() instanceof Player player ? player : null);
    }

    /** Only the output is shielded: explosions inside the mechanism may be part of the farm itself. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isProtectedOutput);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isProtectedOutput);
    }

    private boolean isOutput(InventoryHolder holder) {
        return holder instanceof BlockInventoryHolder block && isProtectedOutput(block.getBlock());
    }

    private boolean isProtectedOutput(Block block) {
        var position = PaperOutputResolver.position(block);
        return service.activeFarms().stream().anyMatch(farm -> farm.outputBlocks().anyMatch(position::equals));
    }

    private static Player responsiblePlayer(Entity entity) {
        if (entity instanceof Player player) return player;
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        return null;
    }

    private boolean insideMechanism(Entity entity) {
        var box = entity.getBoundingBox();
        var worldId = entity.getWorld().getUID();
        return service.activeFarms().stream().anyMatch(farm -> farm.mechanism().min().worldId().equals(worldId)
                && PaperProfilingWorld.box(farm.mechanism()).overlaps(box));
    }

    private boolean entersMechanism(Player player, Location location) {
        if (location == null || location.getWorld() == null) return false;
        double half = player.getWidth() / 2;
        var box = new BoundingBox(location.getX() - half, location.getY(), location.getZ() - half,
                location.getX() + half, location.getY() + player.getHeight(), location.getZ() + half);
        UUID worldId = location.getWorld().getUID();
        return service.activeFarms().stream().anyMatch(farm -> farm.mechanism().min().worldId().equals(worldId)
                && PaperProfilingWorld.box(farm.mechanism()).overlaps(box));
    }

    private boolean isProtected(Block block) {
        return isProtected(PaperOutputResolver.position(block));
    }

    private boolean isProtected(BlockPosition position) {
        return service.activeFarms().stream().anyMatch(farm -> farm.mechanism().contains(position)
                || farm.outputBlocks().anyMatch(position::equals));
    }

    private static void deny(Cancellable event, Player player) {
        event.setCancelled(true);
        if (player != null) player.sendActionBar(DENIED);
    }
}
