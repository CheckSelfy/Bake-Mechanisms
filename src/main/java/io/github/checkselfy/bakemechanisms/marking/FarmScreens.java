package io.github.checkselfy.bakemechanisms.marking;

import io.github.checkselfy.bakemechanisms.profiling.ProfilingSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * The screen of a player's farms. Each farm is a group of four slots: a block showing its state, the operation its
 * state allows (profile, sprint, stasis or unstasis), long profiling and deletion; a slot whose action the state does not
 * allow stays empty, so every action keeps its column. Operations run the {@code /bake}
 * subcommands as the player, so their rules, creator checks and messages stay those of the commands. Deletion is
 * confirmed by a second click on the same slot. Server thread only.
 */
public final class FarmScreens implements Listener {
    /** Two farms per row, in columns 0–3 and 5–8, on five rows; the last row holds the page arrows. */
    static final int FARMS_PER_PAGE = 10;
    private static final int[] GROUP_COLUMNS = { 0, 5 };
    static final int PREVIOUS_SLOT = 45;
    static final int NEXT_SLOT = 53;
    private static final String CONFIRM = "confirm";

    /** Runs a {@code /bake} subcommand as the player. */
    @FunctionalInterface
    public interface Commands {
        void run(Player player, String... args);
    }

    /** Shows a screen to a player; the Paper implementation is {@link #renderer}. */
    interface Opener {
        void open(Player player, FarmScreen screen);

        /** Shows the new content of an open screen. */
        void refresh(Player player, FarmScreen screen);
    }

    /** An operation on a farm, run as {@code /bake <command> --id <UUID>}. */
    private record Operation(String command, Material icon, String title, String description) { }

    /** Candidates for the slot right of the state block; the first one the farm's state accepts is shown. */
    private static final List<Operation> MAIN = List.of(
            new Operation("profile", Material.CLOCK, "Профилировать",
                    "Замер на " + ProfilingSettings.DURATION_TICKS + " тиков."),
            new Operation("sprint", Material.SUGAR, "Ускорить замер",
                    "Ускоряет весь сервер до конца замера."),
            new Operation("stasis", Material.BLUE_ICE, "В стазис", "Механизм заменится заполнителем."),
            new Operation("unstasis", Material.CAMPFIRE, "Из стазиса", "Механизм вернётся; нужен новый замер."));

    private static final Operation LONG_PROFILE = new Operation("profile-long", Material.SPYGLASS,
            "Долгое профилирование", "Замер на " + ProfilingSettings.LONG_DURATION_TICKS + " тиков, точнее.");

    private static final Map<FarmRegistry.State, Material> BLOCKS = Map.of(
            FarmRegistry.State.NEEDS_PROFILING, Material.YELLOW_CONCRETE,
            FarmRegistry.State.PROFILING, Material.OBSERVER,
            FarmRegistry.State.READY, Material.EMERALD_BLOCK,
            FarmRegistry.State.STASIS, Material.BEDROCK);

    private final FarmRegistry registry;
    private final Commands commands;
    private final Opener opener;
    /** Runs a task on the next server tick: inventories must not be opened, closed or changed inside a click. */
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
        return new Opener() {
            @Override
            public void open(Player player, FarmScreen screen) {
                screen.inventory(server.createInventory(screen, FarmScreen.SIZE, Component.text(FarmScreen.TITLE)));
                refresh(player, screen);
                player.openInventory(screen.getInventory());
            }

            @Override
            public void refresh(Player player, FarmScreen screen) {
                var inventory = screen.getInventory();
                inventory.clear();
                screen.icons().forEach((slot, icon) ->
                        inventory.setItem(slot, ButtonMenu.item(icon.material(), icon.name(), icon.lore(), false)));
            }
        };
    }

    /** Opens the first page of the player's farms. Not for use inside an inventory click. */
    public void openList(Player player) {
        var screen = new FarmScreen();
        screen.replaceWith(page(player, screen, 0, null));
        opener.open(player, screen);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        var press = ButtonMenu.press(event, FarmScreen.class);
        if (press == null) return;
        var button = press.menu().button(press.slot());
        if (button == null) return;
        if (button.leaves()) press.menu().close();
        button.action().run();
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        ButtonMenu.cancelDrag(event, FarmScreen.class);
    }

    /** Shows another page of the open screen, on the next tick. */
    private void show(Player player, FarmScreen screen, int page, UUID deleting) {
        nextTick.accept(() -> {
            screen.replaceWith(page(player, screen, page, deleting));
            opener.refresh(player, screen);
        });
    }

    /**
     * @param deleting the farm whose deletion was requested and awaits the confirming click, or null
     */
    private FarmScreen page(Player player, FarmScreen open, int page, UUID deleting) {
        var farms = registry.findByCreator(player.getUniqueId());
        var content = new FarmScreen();
        if (farms.isEmpty()) {
            return content.show(22, new FarmScreen.Icon(Material.PAPER, "Ферм пока нет",
                    List.of("Разметьте ферму: /bake wand,", "затем ПКМ выделителем — меню.")));
        }
        int pages = (farms.size() + FARMS_PER_PAGE - 1) / FARMS_PER_PAGE;
        int current = Math.clamp(page, 0, pages - 1);
        int first = current * FARMS_PER_PAGE;
        for (int i = first; i < Math.min(farms.size(), first + FARMS_PER_PAGE); i++) {
            int group = i - first;
            int slot = group / GROUP_COLUMNS.length * 9 + GROUP_COLUMNS[group % GROUP_COLUMNS.length];
            group(player, open, content, farms.get(i), slot, current, deleting);
        }
        if (current > 0) {
            content.button(PREVIOUS_SLOT, new FarmScreen.Icon(Material.ARROW, "Назад",
                    List.of("Страница " + current + " из " + pages)), () -> show(player, open, current - 1, null));
        }
        if (current < pages - 1) {
            content.button(NEXT_SLOT, new FarmScreen.Icon(Material.ARROW, "Дальше",
                    List.of("Страница " + (current + 2) + " из " + pages)), () -> show(player, open, current + 1, null));
        }
        return content;
    }

    /** The state block at the slot, followed by the operation and deletion the state allows. */
    private void group(Player player, FarmScreen open, FarmScreen content, FarmRegistry.MarkedFarm farm, int slot,
                       int page, UUID deleting) {
        var id = farm.id().toString();
        content.show(slot, new FarmScreen.Icon(BLOCKS.get(farm.state()), name(farm), describe(farm)));
        MAIN.stream().filter(operation -> FarmOperations.accepts(operation.command(), farm.state()))
                .filter(operation -> allowed(player, operation)).findFirst()
                .ifPresent(operation -> operation(player, content, slot + 1, operation, id));
        if (FarmOperations.accepts(LONG_PROFILE.command(), farm.state())) {
            operation(player, content, slot + 2, LONG_PROFILE, id);
        }
        if (!FarmOperations.accepts("unregister", farm.state())) return;
        if (farm.id().equals(deleting)) {
            content.button(slot + 3, new FarmScreen.Icon(Material.TNT, "Точно удалить?",
                    List.of("Нажмите ещё раз в течение " + FarmRemoval.CONFIRMATION_SECONDS + " с.",
                            "Механизм в мире не изменится.")), () -> {
                        commands.run(player, "unregister", FarmArguments.ID_FLAG, id, CONFIRM);
                        show(player, open, page, null);
                    });
        } else {
            content.button(slot + 3, new FarmScreen.Icon(Material.BARRIER, "Удалить",
                    List.of("Удаляет регистрацию и профиль.", "Попросит подтверждения.")), () -> {
                        commands.run(player, "unregister", FarmArguments.ID_FLAG, id);
                        show(player, open, page, farm.id());
                    });
        }
    }

    /** Sprint runs the vanilla {@code /tick sprint}, so only players allowed to use {@code /tick} get it. */
    private static boolean allowed(Player player, Operation operation) {
        return !operation.command().equals("sprint") || player.hasPermission(MarkingController.TICK_PERMISSION);
    }

    /** A button that closes the screen and runs the operation's command on the farm. */
    private void operation(Player player, FarmScreen content, int slot, Operation operation, String id) {
        content.exit(slot, new FarmScreen.Icon(operation.icon(), operation.title(), List.of(operation.description())),
                () -> nextTick.accept(() -> {
                    player.closeInventory();
                    commands.run(player, operation.command(), FarmArguments.ID_FLAG, id);
                }));
    }

    private static String label(FarmRegistry.State state) {
        return switch (state) {
            case NEEDS_PROFILING -> "Ждёт профилирования";
            case PROFILING -> "Идёт профилирование";
            case READY -> "Готова к стазису";
            case STASIS -> "В стазисе";
        };
    }

    private static String name(FarmRegistry.MarkedFarm farm) {
        return farm.name() != null ? farm.name() : "Без имени";
    }

    /** The state first, then where the farm is and what it produces. */
    private static List<String> describe(FarmRegistry.MarkedFarm farm) {
        var lore = new ArrayList<String>();
        lore.add(label(farm.state()));
        lore.add(MarkingSelection.coordinates(farm.mechanism().min()) + " → "
                + MarkingSelection.coordinates(farm.mechanism().max()));
        if (farm.name() == null) lore.add(farm.id().toString());
        if (farm.profile() != null) {
            var rates = new TreeMap<String, Double>();
            for (var output : farm.profile().outputs()) {
                output.forEach((item, rate) -> rates.merge(item.getKey().getKey(), rate, Double::sum));
            }
            rates.forEach((item, rate) -> lore.add(item + ": " + String.format(Locale.ROOT, "%.2f", rate) + " / 20 тиков"));
        }
        return lore;
    }
}
