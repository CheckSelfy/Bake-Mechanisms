package io.github.checkselfy.bakemechanisms.marking;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Inventory screens of a player's farms: a list showing each farm's state as a block, and per farm the actions its
 * state allows. Actions run the {@code /bake} subcommands as the player, so their rules, creator checks and
 * messages stay those of the commands. Server thread only.
 */
public final class FarmScreens implements Listener {
    static final int FARMS_PER_PAGE = 45;
    static final int PREVIOUS_SLOT = 45;
    static final int NEXT_SLOT = 53;
    static final int STATUS_SLOT = 13;
    static final int PROFILE_SLOT = 28;
    static final int STASIS_SLOT = 30;
    static final int UNSTASIS_SLOT = 32;
    static final int DELETE_SLOT = 34;
    static final int BACK_SLOT = 45;
    static final int CONFIRM_SLOT = 29;
    static final int CANCEL_SLOT = 33;

    /** Runs a {@code /bake} subcommand as the player. */
    @FunctionalInterface
    public interface Commands {
        void run(Player player, String... args);
    }

    /** Shows a screen to a player; the Paper implementation is {@link #renderer}. */
    @FunctionalInterface
    interface Opener {
        void open(Player player, FarmScreen screen);
    }

    /** An operation on one farm, offered on its screen when the farm is in one of the given states. */
    private enum Operation {
        PROFILE(PROFILE_SLOT, Material.CLOCK, "Профилировать", "profile",
                List.of("Замер производства; область и выходные", "контейнеры защищены до его конца."),
                FarmRegistry.State.NEEDS_PROFILING),
        STASIS(STASIS_SLOT, Material.BLUE_ICE, "Ввести в стазис", "stasis",
                List.of("Механизм сохраняется и заменяется", "заполнителем, предметы производит эмуляция."),
                FarmRegistry.State.READY),
        UNSTASIS(UNSTASIS_SLOT, Material.CAMPFIRE, "Вывести из стазиса", "unstasis",
                List.of("Механизм возвращается в мир;", "потребуется новое профилирование."),
                FarmRegistry.State.STASIS),
        DELETE(DELETE_SLOT, Material.LAVA_BUCKET, "Удалить регистрацию", "unregister",
                List.of("Механизм в мире не изменится.", "Попросит подтверждения."),
                FarmRegistry.State.NEEDS_PROFILING, FarmRegistry.State.READY);

        private final int slot;
        private final Material icon;
        private final String title;
        private final String command;
        private final List<String> description;
        private final List<FarmRegistry.State> states;

        Operation(int slot, Material icon, String title, String command, List<String> description,
                  FarmRegistry.State... states) {
            this.slot = slot;
            this.icon = icon;
            this.title = title;
            this.command = command;
            this.description = description;
            this.states = List.of(states);
        }
    }

    private final FarmRegistry registry;
    private final Commands commands;
    private final Opener opener;
    /** Runs a task on the next server tick: inventories must not be opened or closed inside a click event. */
    private final Consumer<Runnable> nextTick;

    public FarmScreens(FarmRegistry registry, Commands commands, Server server, Consumer<Runnable> nextTick) {
        this(registry, commands, renderer(server), nextTick);
    }

    FarmScreens(FarmRegistry registry, Commands commands, Opener opener, Consumer<Runnable> nextTick) {
        this.registry = registry;
        this.commands = commands;
        this.opener = opener;
        this.nextTick = nextTick;
    }

    /** Paper implementation of {@link Opener}. */
    static Opener renderer(Server server) {
        return (player, screen) -> {
            var inventory = server.createInventory(screen, FarmScreen.SIZE, Component.text(screen.title()));
            screen.inventory(inventory);
            screen.icons().forEach((slot, icon) ->
                    inventory.setItem(slot, ButtonMenu.item(icon.material(), icon.name(), icon.lore(), false)));
            player.openInventory(inventory);
        };
    }

    /** Opens the first page of the player's farm list. Not for use inside an inventory click. */
    public void openList(Player player) {
        opener.open(player, list(player, 0));
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        var press = ButtonMenu.press(event, FarmScreen.class);
        if (press == null) return;
        var action = press.menu().action(press.slot());
        if (action == null) return;
        press.menu().close();
        action.run();
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        ButtonMenu.cancelDrag(event, FarmScreen.class);
    }

    private void later(Player player, FarmScreen screen) {
        nextTick.accept(() -> opener.open(player, screen));
    }

    private FarmScreen list(Player player, int page) {
        var farms = registry.findByCreator(player.getUniqueId());
        var screen = new FarmScreen(FarmScreen.Kind.LIST, "Мои фермы", null);
        if (farms.isEmpty()) {
            return screen.show(22, new FarmScreen.Icon(Material.PAPER, "Ферм пока нет",
                    List.of("Разметьте ферму: /bake wand,", "затем ПКМ выделителем — меню разметки.")));
        }
        int pages = (farms.size() + FARMS_PER_PAGE - 1) / FARMS_PER_PAGE;
        int current = Math.clamp(page, 0, pages - 1);
        var shown = farms.subList(current * FARMS_PER_PAGE, Math.min(farms.size(), (current + 1) * FARMS_PER_PAGE));
        for (int i = 0; i < shown.size(); i++) {
            var farm = shown.get(i);
            var lore = new ArrayList<>(describe(farm));
            lore.add("Нажмите — действия с фермой.");
            screen.button(i, new FarmScreen.Icon(block(farm.state()), name(farm), lore),
                    () -> later(player, farm(player, farm)));
        }
        if (current > 0) {
            screen.button(PREVIOUS_SLOT, new FarmScreen.Icon(Material.ARROW, "Назад",
                    List.of("Страница " + current + " из " + pages)), () -> later(player, list(player, current - 1)));
        }
        if (current < pages - 1) {
            screen.button(NEXT_SLOT, new FarmScreen.Icon(Material.ARROW, "Дальше",
                    List.of("Страница " + (current + 2) + " из " + pages)), () -> later(player, list(player, current + 1)));
        }
        return screen;
    }

    private FarmScreen farm(Player player, FarmRegistry.MarkedFarm farm) {
        var screen = new FarmScreen(FarmScreen.Kind.FARM, "Ферма " + name(farm), farm.id())
                .show(STATUS_SLOT, new FarmScreen.Icon(block(farm.state()), name(farm), describe(farm)));
        for (var operation : Operation.values()) {
            if (!operation.states.contains(farm.state())) {
                var lore = new ArrayList<>(operation.description);
                lore.add("Доступно: " + operation.states.stream().map(FarmScreens::label)
                        .collect(Collectors.joining(", ")) + ".");
                lore.add("Сейчас: " + label(farm.state()) + ".");
                screen.show(operation.slot, new FarmScreen.Icon(Material.GRAY_DYE,
                        operation.title + " — недоступно", lore));
                continue;
            }
            var icon = new FarmScreen.Icon(operation.icon, operation.title, operation.description);
            if (operation == Operation.DELETE) {
                screen.button(operation.slot, icon, () -> nextTick.accept(() -> {
                    commands.run(player, operation.command, FarmArguments.ID_FLAG, farm.id().toString());
                    opener.open(player, confirmDelete(player, farm));
                }));
            } else {
                screen.button(operation.slot, icon, () -> nextTick.accept(() -> {
                    player.closeInventory();
                    commands.run(player, operation.command, FarmArguments.ID_FLAG, farm.id().toString());
                }));
            }
        }
        return screen.button(BACK_SLOT, new FarmScreen.Icon(Material.ARROW, "К списку ферм", List.of()),
                () -> later(player, list(player, 0)));
    }

    private FarmScreen confirmDelete(Player player, FarmRegistry.MarkedFarm farm) {
        return new FarmScreen(FarmScreen.Kind.CONFIRM_DELETE, "Удалить ферму " + name(farm) + "?", farm.id())
                .show(STATUS_SLOT, new FarmScreen.Icon(block(farm.state()), name(farm), describe(farm)))
                .button(CONFIRM_SLOT, new FarmScreen.Icon(Material.TNT, "Да, удалить регистрацию",
                        List.of("Удаляются регистрация, профиль и сохранённые данные.",
                                "Механизм в мире не изменится.",
                                "Неперенесённый остаток буфера выработки будет потерян.",
                                "Подтверждение действует " + FarmRemoval.CONFIRMATION_SECONDS + " секунд.")),
                        () -> nextTick.accept(() -> {
                            commands.run(player, Operation.DELETE.command, FarmArguments.ID_FLAG, farm.id().toString(),
                                    "confirm");
                            opener.open(player, list(player, 0));
                        }))
                .button(CANCEL_SLOT, new FarmScreen.Icon(Material.LIME_DYE, "Отмена", List.of("Ферма останется.")),
                        () -> later(player, current(player, farm)));
    }

    /** The farm screen with the farm's current state, or the list when the farm no longer exists. */
    private FarmScreen current(Player player, FarmRegistry.MarkedFarm farm) {
        return registry.findByCreator(player.getUniqueId()).stream().filter(found -> found.id().equals(farm.id()))
                .findFirst().map(found -> farm(player, found)).orElseGet(() -> list(player, 0));
    }

    private static final Map<FarmRegistry.State, Material> BLOCKS = Map.of(
            FarmRegistry.State.NEEDS_PROFILING, Material.YELLOW_CONCRETE,
            FarmRegistry.State.PROFILING, Material.OBSERVER,
            FarmRegistry.State.READY, Material.EMERALD_BLOCK,
            FarmRegistry.State.STASIS, Material.BEDROCK);

    /** The block that stands for a state; stasis is bedrock like its filler. */
    static Material block(FarmRegistry.State state) {
        return BLOCKS.get(state);
    }

    private static String label(FarmRegistry.State state) {
        return switch (state) {
            case NEEDS_PROFILING -> "нужно профилирование";
            case PROFILING -> "идёт профилирование";
            case READY -> "готова к стазису";
            case STASIS -> "в стазисе";
        };
    }

    private static String name(FarmRegistry.MarkedFarm farm) {
        return farm.name() != null ? farm.name() : "Без имени";
    }

    private static List<String> describe(FarmRegistry.MarkedFarm farm) {
        var lore = new ArrayList<String>();
        lore.add("Состояние: " + label(farm.state()) + " (" + farm.state() + ")");
        lore.add("Механизм: " + MarkingSelection.coordinates(farm.mechanism().min()) + " → "
                + MarkingSelection.coordinates(farm.mechanism().max()));
        lore.add("Выходных контейнеров: " + farm.outputs().size());
        if (farm.name() == null) lore.add("UUID: " + farm.id());
        if (farm.profile() != null) {
            lore.add("Продукция, предметов / 20 тиков:");
            var rates = new TreeMap<String, Double>();
            for (var output : farm.profile().outputs()) {
                output.forEach((item, rate) -> rates.merge(item.getKey().getKey(), rate, Double::sum));
            }
            rates.forEach((item, rate) -> lore.add("  " + item + ": " + String.format(Locale.ROOT, "%.4f", rate)));
        }
        return lore;
    }
}
