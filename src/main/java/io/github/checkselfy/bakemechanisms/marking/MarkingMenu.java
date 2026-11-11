package io.github.checkselfy.bakemechanisms.marking;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * The wand's inventory menu. Its items are buttons only: every click and drag on an open menu is cancelled by
 * {@link MarkingController}, which looks the clicked slot up with {@link Button#at(int)}. Server thread only.
 */
public final class MarkingMenu implements InventoryHolder {
    static final int SIZE = 27;
    static final Component TITLE = Component.text("Разметка фермы");
    private Inventory inventory;
    /** Set once a button that closes the menu was pressed, so further clicks before the close do nothing. */
    private boolean closing;

    /** Shows the menu to a player; the Paper implementation is {@link #render}. */
    @FunctionalInterface
    interface Opener {
        void open(Player player, MarkingMenu menu, MarkingSelection selection);
    }

    enum Button {
        FIRST_CORNER(10, Material.LIME_BANNER, "Выбрать первый угол"),
        SECOND_CORNER(11, Material.ORANGE_BANNER, "Выбрать второй угол"),
        OUTPUT(12, Material.CHEST, "Назначить выходной контейнер"),
        REGISTER(14, Material.WRITABLE_BOOK, "Зарегистрировать ферму"),
        UNSELECT(15, Material.BARRIER, "Очистить выделение"),
        HELP(16, Material.BOOK, "Подсказка");

        private final int slot;
        private final Material icon;
        private final String title;

        Button(int slot, Material icon, String title) {
            this.slot = slot;
            this.icon = icon;
            this.title = title;
        }

        int slot() {
            return slot;
        }

        static Optional<Button> at(int slot) {
            return Arrays.stream(values()).filter(button -> button.slot == slot).findFirst();
        }

        /** The wand mode this button selects, or null for a button that runs an action. */
        MarkingSelection.Mode mode() {
            return switch (this) {
                case FIRST_CORNER -> MarkingSelection.Mode.FIRST_CORNER;
                case SECOND_CORNER -> MarkingSelection.Mode.SECOND_CORNER;
                case OUTPUT -> MarkingSelection.Mode.OUTPUT;
                default -> null;
            };
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    boolean closing() {
        return closing;
    }

    void close() {
        closing = true;
    }

    /** Paper implementation of {@link Opener}. */
    static Opener renderer(Server server) {
        return (player, menu, selection) -> {
            menu.inventory = server.createInventory(menu, SIZE, TITLE);
            for (var button : Button.values()) menu.inventory.setItem(button.slot, item(button, selection));
            player.openInventory(menu.inventory);
        };
    }

    private static ItemStack item(Button button, MarkingSelection selection) {
        var item = new ItemStack(button.icon);
        var meta = item.getItemMeta();
        meta.displayName(plain(button.title, NamedTextColor.GOLD));
        meta.lore(lore(button, selection).stream().map(line -> plain(line, NamedTextColor.GRAY)).toList());
        if (button.mode() != null && button.mode() == selection.mode()) meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    private static List<String> lore(Button button, MarkingSelection selection) {
        var lore = new ArrayList<String>();
        switch (button) {
            case FIRST_CORNER -> lore.add("Сейчас: " + MarkingSelection.coordinates(selection.first()));
            case SECOND_CORNER -> lore.add("Сейчас: " + MarkingSelection.coordinates(selection.second()));
            case OUTPUT -> {
                lore.add("Выбрано: " + selection.outputs().size());
                lore.add("ЛКМ по выбранному контейнеру убирает его.");
            }
            case REGISTER -> {
                lore.add(selection.complete() ? "Всё выбрано." : "Не хватает: " + String.join(", ", selection.missing()));
                lore.add("Имя можно задать потом: /bake rename.");
            }
            case UNSELECT -> lore.add("Сбрасывает углы и выходные контейнеры.");
            case HELP -> lore.add("Показывает порядок разметки в чате.");
        }
        if (button.mode() != null) {
            lore.add(button.mode() == selection.mode() ? "Текущий режим." : "Нажмите, затем ЛКМ по блоку.");
        }
        return lore;
    }

    private static Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
