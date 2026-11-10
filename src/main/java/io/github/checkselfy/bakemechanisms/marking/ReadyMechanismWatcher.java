package io.github.checkselfy.bakemechanisms.marking;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * A player who changes a READY mechanism invalidates its profile: blocks broken, placed or filled with a bucket,
 * and interactive blocks such as levers, repeaters and comparators used, inside the cuboid. The farm's own work
 * (redstone, pistons, growth) never counts. Runs after protection, so cancelled actions are ignored.
 * All callbacks run on the server thread.
 */
public final class ReadyMechanismWatcher implements Listener {
    private final FarmRegistry registry;
    private final Server server;

    public ReadyMechanismWatcher(FarmRegistry registry, Server server) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.server = Objects.requireNonNull(server, "server");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) { changed(event.getPlayer(), event.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event instanceof BlockMultiPlaceEvent multi) {
            multi.getReplacedBlockStates().forEach(state -> changed(event.getPlayer(), state.getBlock()));
        } else {
            changed(event.getPlayer(), event.getBlockPlaced());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) { changed(event.getPlayer(), event.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) { changed(event.getPlayer(), event.getBlockClicked()); }

    /**
     * Cancelled here means the interacted block was not used (the marking wand denies it, for example).
     * {@code isInteractable} is deprecated only as approximate; erring towards invalidation is safe.
     */
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        var block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null || !block.getType().isInteractable()) return;
        // Sneaking with an item in either hand skips the block.
        var inventory = event.getPlayer().getInventory();
        if (event.getPlayer().isSneaking()
                && (!inventory.getItemInMainHand().isEmpty() || !inventory.getItemInOffHand().isEmpty())) return;
        changed(event.getPlayer(), block);
    }

    private void changed(Player player, Block block) {
        for (var farm : registry.invalidateReady(PaperOutputResolver.position(block))) {
            var message = "Механизм фермы " + farm.id() + " изменён: профиль недействителен, состояние "
                    + farm.state() + ". Перед стазисом запустите новое профилирование.";
            player.sendMessage(message);
            if (!farm.creatorId().equals(player.getUniqueId())) notifyCreator(farm.creatorId(), message);
        }
    }

    private void notifyCreator(UUID creatorId, String message) {
        var creator = server.getPlayer(creatorId);
        if (creator != null) creator.sendMessage(message);
    }
}
