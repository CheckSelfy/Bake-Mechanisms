package io.github.checkselfy.bakemechanisms.marking;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * An inventory screen whose items are buttons only. While one is open, every click and drag is cancelled,
 * including clicks in the player's own inventory below it, so its items can be neither taken nor moved and nothing
 * can be shift-clicked in. Only a plain left or right click on the screen itself presses a button, and a button
 * that leaves the screen latches it, so further clicks before it closes do nothing. Server thread only.
 */
abstract class ButtonMenu implements InventoryHolder {
    private Inventory inventory;
    private boolean closing;

    /** A plain click on a button slot of an open menu; {@code right} for a right click. */
    record Press<M extends ButtonMenu>(Player player, M menu, int slot, boolean right) { }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    void inventory(Inventory inventory) {
        this.inventory = inventory;
    }

    boolean closing() {
        return closing;
    }

    /** Ignores every further click on this menu; call it when a button leaves the menu. */
    void close() {
        closing = true;
    }

    /**
     * Cancels a click on an open menu of the given type.
     *
     * @return the press, or null when the click is not on such a menu, is not a plain click on the menu itself, or
     *         the menu is already closing
     */
    static <M extends ButtonMenu> Press<M> press(InventoryClickEvent event, Class<M> type) {
        var top = event.getView().getTopInventory();
        if (!type.isInstance(top.getHolder(false))) return null;
        event.setCancelled(true);
        var menu = type.cast(top.getHolder(false));
        if (!(event.getWhoClicked() instanceof Player player) || menu.closing()) return null;
        if (event.getClickedInventory() != top) return null;
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return null;
        return new Press<>(player, menu, event.getRawSlot(), event.getClick() == ClickType.RIGHT);
    }

    /** A button item: gold name, gray lore without the default italics, optionally glinting. */
    static ItemStack item(Material material, String name, List<String> lore, boolean glint) {
        var item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.displayName(text(name, NamedTextColor.GOLD));
        meta.lore(lore.stream().map(line -> text(line, NamedTextColor.GRAY)).toList());
        if (glint) meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    private static Component text(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    static void cancelDrag(InventoryDragEvent event, Class<? extends ButtonMenu> type) {
        if (type.isInstance(event.getView().getTopInventory().getHolder(false))) event.setCancelled(true);
    }
}
