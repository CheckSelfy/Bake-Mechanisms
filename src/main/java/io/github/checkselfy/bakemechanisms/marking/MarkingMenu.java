package io.github.checkselfy.bakemechanisms.marking;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;

/**
 * The wand's inventory menu. {@link MarkingController} handles its clicks and looks the clicked slot up with
 * {@link Button#at(int)}. Server thread only.
 */
public final class MarkingMenu extends ButtonMenu {
    static final int SIZE = 27;
    static final Component TITLE = Component.text("Разметка фермы");

    /** Shows the menu to a player; the Paper implementation is {@link #render}. */
    @FunctionalInterface
    interface Opener {
        void open(Player player, MarkingMenu menu, MarkingSelection selection);
    }

    enum Button {
        FIRST_CORNER(10, Material.LIME_BANNER, "Выбрать первый угол"),
        SECOND_CORNER(11, Material.ORANGE_BANNER, "Выбрать второй угол"),
        OUTPUT(12, Material.CHEST, "Назначить выходной контейнер"),
        SELECT_LOOKED(13, Material.ENDER_EYE, "Контейнер, на который смотрю"),
        REGISTER(14, Material.WRITABLE_BOOK, "Зарегистрировать ферму"),
        UNSELECT(15, Material.BARRIER, "Очистить выделение"),
        HELP(16, Material.BOOK, "Подсказка"),
        FARMS(21, Material.BOOKSHELF, "Мои фермы"),
        REGISTER_NAMED(23, Material.NAME_TAG, "Зарегистрировать с именем");

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

    /** Paper implementation of {@link Opener}. */
    static Opener renderer(Server server) {
        return (player, menu, selection) -> {
            var inventory = server.createInventory(menu, SIZE, TITLE);
            menu.inventory(inventory);
            for (var button : Button.values()) {
                inventory.setItem(button.slot, item(button.icon, button.title, lore(button, selection),
                        button.mode() != null && button.mode() == selection.mode()));
            }
            player.openInventory(inventory);
        };
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
            case FARMS -> lore.add("Состояние ваших ферм и действия с ними.");
            case SELECT_LOOKED -> {
                lore.add("Добавляет или убирает контейнер,");
                lore.add("на который вы смотрите (до 6 блоков).");
            }
            case REGISTER_NAMED -> {
                lore.add(selection.complete() ? "Всё выбрано." : "Не хватает: " + String.join(", ", selection.missing()));
                lore.add("Откроет поле для ввода имени.");
            }
        }
        if (button.mode() != null) {
            lore.add(button.mode() == selection.mode() ? "Текущий режим." : "Нажмите, затем ЛКМ по блоку.");
        }
        return lore;
    }
}
