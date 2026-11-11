package io.github.checkselfy.bakemechanisms.marking;

import io.github.checkselfy.bakemechanisms.profiling.ProfilingService;
import io.github.checkselfy.bakemechanisms.profiling.ProfilingSettings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Command and wand boundary. All callbacks run on the server thread. */
public final class MarkingController implements Listener, TabExecutor {
    private static final List<String> COMMANDS = List.of("wand", "select-output", "unselect", "register", "list", "manage",
            "info", "rename", "profile", "profile-long", "sprint", "cancel-profiling", "unregister");
    private static final List<String> FARM_COMMANDS = List.of("info", "rename", "profile", "profile-long", "sprint",
            "cancel-profiling", "unregister");
    /**
     * Former names of renamed commands. They are not aliases: each answers with its new name and does nothing, so
     * an old habit never silently runs a different operation.
     */
    static final Map<String, String> RENAMED = Map.of("output", "select-output", "clear", "unselect",
            "cancel", "cancel-profiling", "restore", "unstasis", "delete", "unregister");
    private static final String FARM_USAGE = FarmArguments.USAGE;
    private static final String HELP = "ПКМ выделителем — меню разметки. /bake wand | select-output | unselect | register [имя] | list | manage | info "
            + FARM_USAGE + " | rename " + FARM_USAGE + " <имя> | profile " + FARM_USAGE + " | profile-long " + FARM_USAGE + " | sprint " + FARM_USAGE
            + " (ускоряет весь сервер) | cancel-profiling " + FARM_USAGE + " | stasis " + FARM_USAGE + " | unstasis "
            + FARM_USAGE + " | unregister " + FARM_USAGE + " [confirm]";
    private static final String CONFIRM = "confirm";
    static final String GUIDE = "Разметка выделителем: ПКМ открывает меню. В меню выберите, что отмечать, затем ЛКМ по"
            + " блоку: первый угол, второй угол, выходной контейнер (повторный выбор контейнера убирает его). После"
            + " каждого выбора выделитель сам переходит к тому, чего не хватает. Когда всё выбрано — «Зарегистрировать"
            + " ферму» в меню или /bake register [имя]. Состояние видно над панелью предметов.";
    /** Permission of the vanilla {@code /tick} command; {@code /bake sprint} needs it too. */
    static final String TICK_PERMISSION = "minecraft.command.tick";
    private final JavaPlugin plugin;
    private final FarmRegistry registry;
    private final PaperOutputResolver outputs;
    private final ProfilingService profiling;
    private final FarmRemoval removal;
    private final NamespacedKey wandKey;
    private final MarkingMenu.Opener menus;
    /** Runs a task on the next server tick: inventories must not be opened or closed inside a click event. */
    private final Consumer<Runnable> nextTick;
    /** Opens the farm screens; not inside an inventory click. */
    private final Consumer<Player> openFarms;
    private final Map<UUID, MarkingSelection> selections = new HashMap<>();
    /** The last wand click of each player, to drop the repeated events one physical click can produce. */
    private final Map<UUID, WandClick> lastClicks = new HashMap<>();

    /** A wand click: the block for a left click, null for a right click that opens the menu. */
    private record WandClick(BlockPosition block, int tick) { }

    /**
     * Ticks within which a repeated click is the same physical click: a right click on a block is followed by a
     * right click in the air, and a held left click in creative repeats every few ticks.
     */
    static final int REPEAT_TICKS = 6;
    /** How long the result of a step stays in the action bar before the current status replaces it. */
    static final int NOTICE_TICKS = 60;
    /**
     * Longest action-bar line, in characters. The bar is not wrapped: at GUI scale 4 on a 1920-pixel-wide screen
     * about 80 characters fit, so lines are kept well under that.
     */
    static final int ACTION_BAR_LIMIT = 64;
    private final Map<UUID, Notice> notices = new HashMap<>();

    private record Notice(Component text, int until) { }

    public MarkingController(JavaPlugin plugin, FarmRegistry registry, PaperOutputResolver outputs,
                             ProfilingService profiling, FarmRemoval removal, Consumer<Player> openFarms) {
        this(plugin, registry, outputs, profiling, removal, MarkingMenu.renderer(plugin.getServer()),
                task -> plugin.getServer().getScheduler().runTask(plugin, task), openFarms);
    }

    MarkingController(JavaPlugin plugin, FarmRegistry registry, PaperOutputResolver outputs,
                      ProfilingService profiling, FarmRemoval removal, MarkingMenu.Opener menus,
                      Consumer<Runnable> nextTick, Consumer<Player> openFarms) {
        this.plugin = plugin;
        this.registry = registry;
        this.outputs = outputs;
        this.profiling = profiling;
        this.removal = removal;
        this.menus = menus;
        this.nextTick = nextTick;
        this.openFarms = openFarms;
        this.wandKey = new NamespacedKey(plugin, "marking_wand");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Команды разметки доступны только игрокам.");
            return true;
        }
        try {
            if (args.length == 0) {
                player.sendMessage(HELP);
                return true;
            }
            var actor = player.getUniqueId();
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "wand" -> {
                    requireArguments(args, 1);
                    giveWand(player);
                }
                case "select-output" -> {
                    requireArguments(args, 1);
                    Block block = player.getTargetBlockExact(6);
                    if (block == null) throw new IllegalArgumentException("Посмотрите на выходной контейнер в пределах 6 блоков.");
                    selectOutput(player, block);
                }
                case "register" -> {
                    if (args.length > 2) throw new IllegalArgumentException("/bake register [имя]");
                    register(player, args.length == 2 ? args[1] : null);
                }
                case "manage" -> {
                    requireArguments(args, 1);
                    openFarms.accept(player);
                }
                case "list" -> {
                    requireArguments(args, 1);
                    var farms = registry.findByCreator(actor);
                    if (farms.isEmpty()) player.sendMessage("У вас нет зарегистрированных ферм.");
                    for (var farm : farms) show(player, farm);
                }
                case "info" -> show(player, farm(player, args, "/bake info " + FARM_USAGE).farm());
                case "rename" -> {
                    var target = farm(player, args, "/bake rename " + FARM_USAGE + " <имя>", 1);
                    var renamed = registry.rename(target.farm().id(), actor, target.rest().getFirst());
                    player.sendMessage("Ферма переименована: " + renamed.title() + ".");
                }
                case "profile", "profile-long" -> {
                    var farm = farm(player, args, "/bake " + args[0] + " " + FARM_USAGE).farm();
                    if (args[0].equalsIgnoreCase("profile-long")) profiling.start(farm.id(), actor, ProfilingSettings.LONG_DURATION_TICKS);
                    else profiling.start(farm.id(), actor);
                    player.sendMessage("Профилирование запущено на " + profiling.remainingTicks(farm.id(), actor)
                            + " тиков. Область и выходные контейнеры защищены до завершения. Ускорить: /bake sprint "
                            + farm.reference());
                    show(player, registry.requireCreator(farm.id(), actor));
                }
                case "sprint" -> {
                    requireTickPermission(player);
                    sprint(player, farm(player, args, "/bake sprint " + FARM_USAGE).farm());
                }
                case "cancel-profiling" -> {
                    var farm = farm(player, args, "/bake cancel-profiling " + FARM_USAGE).farm();
                    profiling.cancel(farm.id(), actor);
                    player.sendMessage("Профилирование отменено, механизм не изменён.");
                    show(player, registry.requireCreator(farm.id(), actor));
                }
                case "unregister" -> {
                    var usage = "/bake unregister " + FARM_USAGE + " [" + CONFIRM + "]";
                    var target = farm(player, args, usage, -1);
                    var farm = target.farm();
                    if (target.rest().size() == 1 && target.rest().getFirst().equalsIgnoreCase(CONFIRM)) {
                        removal.confirm(farm.id(), actor);
                        player.sendMessage("Регистрация фермы " + farm.title() + " удалена вместе с профилем и"
                                + " данными. Механизм остался в мире без изменений.");
                    } else if (target.rest().isEmpty()) {
                        show(player, removal.request(farm.id(), actor));
                        player.sendMessage("Удалить регистрацию, профиль и сохранённые данные фермы? Механизм в мире"
                                + " не изменится; неперенесённый остаток буфера выработки будет потерян. Подтвердите в"
                                + " течение " + FarmRemoval.CONFIRMATION_SECONDS + " секунд: /bake unregister "
                                + farm.reference() + " " + CONFIRM);
                    } else {
                        throw new IllegalArgumentException(usage);
                    }
                }
                case "unselect" -> {
                    requireArguments(args, 1);
                    unselect(player);
                }
                default -> {
                    var former = args[0].toLowerCase(Locale.ROOT);
                    var renamed = RENAMED.get(former);
                    player.sendMessage(renamed == null ? HELP : "Команда /bake " + former
                            + " переименована: используйте /bake " + renamed + ".");
                }
            }
        } catch (IllegalArgumentException error) {
            player.sendMessage(error.getMessage());
        }
        return true;
    }

    private static void requireArguments(String[] args, int count) {
        if (args.length != count) throw new IllegalArgumentException(HELP);
    }

    /** The farm named after the subcommand, followed by no further arguments. */
    private FarmTarget farm(Player player, String[] args, String usage) {
        return farm(player, args, usage, 0);
    }

    /**
     * @param extra how many arguments must follow the farm reference, or -1 for any number
     */
    private FarmTarget farm(Player player, String[] args, String usage, int extra) {
        if (args.length < 2) throw new IllegalArgumentException(usage);
        var parsed = FarmArguments.parse(args, 1);
        if (extra >= 0 && parsed.rest().size() != extra) throw new IllegalArgumentException(usage);
        return new FarmTarget(registry.require(parsed.farm(), player.getUniqueId()), parsed.rest());
    }

    private record FarmTarget(FarmRegistry.MarkedFarm farm, List<String> rest) { }

    private static void requireTickPermission(Player player) {
        if (!player.hasPermission(TICK_PERMISSION)) {
            throw new IllegalArgumentException("Недостаточно прав: /bake sprint использует /tick sprint, а вам"
                    + " не разрешено выполнять /tick.");
        }
    }

    /**
     * Speeds the server up to the end of the farm's profiling with the vanilla {@code /tick sprint}, run as the
     * player: only players allowed to use {@code /tick} can do it.
     */
    private void sprint(Player player, FarmRegistry.MarkedFarm farm) {
        long remaining = profiling.remainingTicks(farm.id(), player.getUniqueId());
        if (remaining < 1) {
            player.sendMessage("Профилирование фермы " + farm.title() + " уже завершается.");
            return;
        }
        if (!player.performCommand("tick sprint " + remaining)) {
            throw new IllegalArgumentException("Ускорить сервер не удалось: команда /tick sprint не выполнена.");
        }
        player.sendMessage("Ускоряем сервер на " + remaining + " тиков до конца профилирования фермы "
                + farm.title() + ". Ускорение действует на весь сервер, включая другие профилируемые фермы.");
    }

    private void giveWand(Player player) {
        var wand = new ItemStack(Material.BLAZE_ROD);
        var meta = wand.getItemMeta();
        meta.displayName(Component.text("Выделитель Bake Mechanisms"));
        meta.lore(List.of(Component.text("ПКМ — меню разметки"), Component.text("ЛКМ по блоку — выбрать блок")));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        wand.setItemMeta(meta);
        if (!player.getInventory().addItem(wand).isEmpty()) {
            throw new IllegalArgumentException("Освободите место в инвентаре для выделителя.");
        }
        player.sendMessage(GUIDE);
    }

    private boolean isWand(ItemStack item) {
        return item != null && item.getType() == Material.BLAZE_ROD && item.hasItemMeta()
                && Byte.valueOf((byte) 1).equals(item.getItemMeta().getPersistentDataContainer()
                .get(wandKey, PersistentDataType.BYTE));
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() == EquipmentSlot.OFF_HAND && isWand(event.getPlayer().getInventory().getItemInMainHand())) {
            // A click with the wand also reaches the off hand: it must not place or use what the off hand holds.
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
            return;
        }
        if (!isWand(event.getItem())) return;
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        if (event.getHand() != EquipmentSlot.HAND) return;
        var player = event.getPlayer();
        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (!repeated(player, null)) menus.open(player, new MarkingMenu(), selection(player));
            return;
        }
        if (event.getAction() != Action.LEFT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        try {
            if (!repeated(player, PaperOutputResolver.position(event.getClickedBlock()))) {
                apply(player, event.getClickedBlock());
            }
        } catch (IllegalArgumentException error) {
            failed(player, error.getMessage());
        }
    }

    /** Records the click and tells whether it repeats the previous one; a held click keeps being a repeat. */
    private boolean repeated(Player player, BlockPosition block) {
        int tick = plugin.getServer().getCurrentTick();
        var last = lastClicks.put(player.getUniqueId(), new WandClick(block, tick));
        return last != null && Objects.equals(last.block(), block) && tick - last.tick() < REPEAT_TICKS;
    }

    /**
     * Every click on an open menu is cancelled, including clicks in the player's own inventory below it, so menu
     * items can be neither taken nor moved and nothing can be shift-clicked in. Only a plain left or right click on a
     * menu button presses it.
     */
    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        var top = event.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof MarkingMenu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || menu.closing()) return;
        if (event.getClickedInventory() != top) return;
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;
        MarkingMenu.Button.at(event.getRawSlot()).ifPresent(button -> press(player, menu, button));
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof MarkingMenu) event.setCancelled(true);
    }

    private void press(Player player, MarkingMenu menu, MarkingMenu.Button button) {
        menu.close();
        try {
            switch (button) {
                case FIRST_CORNER, SECOND_CORNER, OUTPUT -> {
                    closeMenu(player);
                    selections.put(player.getUniqueId(), selection(player).withMode(button.mode()));
                    notice(player, "Режим: " + button.mode().label() + " → ЛКМ по блоку", NamedTextColor.YELLOW);
                }
                case REGISTER -> {
                    closeMenu(player);
                    register(player, null);
                }
                case UNSELECT -> {
                    unselect(player);
                    // Shown again with the empty selection, unless the player closed the menu or left meanwhile.
                    nextTick.accept(() -> {
                        if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder(false) == menu) {
                            menus.open(player, new MarkingMenu(), selection(player));
                        }
                    });
                }
                case HELP -> {
                    closeMenu(player);
                    player.sendMessage(GUIDE);
                }
                case FARMS -> nextTick.accept(() -> openFarms.accept(player));
            }
        } catch (IllegalArgumentException error) {
            failed(player, error.getMessage());
        }
    }

    private void closeMenu(Player player) {
        nextTick.accept(player::closeInventory);
    }

    private void unselect(Player player) {
        selections.remove(player.getUniqueId());
        player.sendMessage("Текущее выделение очищено.");
        notice(player, "✔ Выделение очищено → ЛКМ по блоку: первый угол", NamedTextColor.GREEN);
    }

    private void failed(Player player, String message) {
        player.sendMessage(message);
        var line = "✖ " + message;
        notice(player, line.length() <= ACTION_BAR_LIMIT ? line : "✖ Отказ, причина в чате", NamedTextColor.RED);
    }

    /** Shows the result of a step in the action bar; {@link #refreshActionBars} keeps it there for a while. */
    private void notice(Player player, String text, NamedTextColor color) {
        var notice = new Notice(Component.text(text, color), plugin.getServer().getCurrentTick() + NOTICE_TICKS);
        notices.put(player.getUniqueId(), notice);
        player.sendActionBar(notice.text());
    }

    /**
     * Keeps the action bar of every player holding the wand up to date: the result of the last step while it is
     * fresh, then the mode, chosen corners, outputs and next step. Called about once a second.
     */
    public void refreshActionBars() {
        int tick = plugin.getServer().getCurrentTick();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!isWand(player.getInventory().getItemInMainHand())) continue;
            var notice = notices.get(player.getUniqueId());
            if (notice != null && notice.until() > tick) {
                player.sendActionBar(notice.text());
            } else {
                notices.remove(player.getUniqueId());
                player.sendActionBar(Component.text(selection(player).status(), NamedTextColor.YELLOW));
            }
        }
    }

    /** Selects the clicked block as what the wand mode names, then moves the wand to what is still missing. */
    private void apply(Player player, Block clicked) {
        var mode = selection(player).mode();
        if (mode == MarkingSelection.Mode.OUTPUT) {
            selectOutput(player, clicked);
            return;
        }
        var block = PaperOutputResolver.position(clicked);
        var old = selection(player);
        var other = mode == MarkingSelection.Mode.FIRST_CORNER ? old.second() : old.first();
        if (other != null && !other.worldId().equals(block.worldId())) {
            throw new IllegalArgumentException("Углы должны находиться в одном мире. Сброс: /bake unselect.");
        }
        selections.put(player.getUniqueId(), old.withCorner(mode, block).advanced());
        player.sendMessage(capitalized(mode.label()) + ": " + coordinates(block));
        notice(player, "✔ Угол " + (mode == MarkingSelection.Mode.FIRST_CORNER ? 1 : 2) + ": " + coordinates(block)
                + " → " + selection(player).hint(), NamedTextColor.GREEN);
    }

    private static String capitalized(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** Adds the container to the selection, or removes it when it is already selected. */
    private void selectOutput(Player player, Block block) {
        var output = outputs.resolve(block);
        var old = selection(player);
        var selected = new ArrayList<>(old.outputs());
        boolean removed = selected.removeIf(entry -> entry.output().equals(output));
        if (!removed) selected.add(new MarkingSelection.SelectedOutput(PaperOutputResolver.position(block), output));
        selections.put(player.getUniqueId(), old.withOutputs(selected).advanced());
        player.sendMessage((removed ? "Выходной контейнер убран из выделения: " : "Выходной контейнер добавлен: ")
                + output.type() + ", блоков: " + output.blocks().size() + ". Выбрано выходных контейнеров: " + selected.size()
                + ". Повторный выбор контейнера убирает его.");
        notice(player, "✔ Контейнер " + (removed ? "убран" : "добавлен") + " (" + selected.size() + ") → "
                + selection(player).hint(), NamedTextColor.GREEN);
    }

    private MarkingSelection selection(Player player) {
        return selections.getOrDefault(player.getUniqueId(), MarkingSelection.empty());
    }

    private void register(Player player, String name) {
        var selection = selection(player);
        if (selection.first() == null || selection.second() == null || selection.outputs().isEmpty()) {
            throw new IllegalArgumentException("Выберите оба угла и хотя бы один выходной контейнер выделителем.");
        }
        var mechanism = new Cuboid(selection.first(), selection.second());
        var world = plugin.getServer().getWorld(mechanism.min().worldId());
        if (world == null || !world.getUID().equals(player.getWorld().getUID())) {
            throw new IllegalArgumentException("Мир выделения недоступен или вы находитесь в другом мире.");
        }
        if (mechanism.min().y() < world.getMinHeight() || mechanism.max().y() >= world.getMaxHeight()) {
            throw new IllegalArgumentException("Кубоид выходит за допустимую высоту мира.");
        }
        var current = new ArrayList<OutputContainer>();
        for (var selected : selection.outputs()) {
            var anchor = selected.anchor();
            if (!anchor.worldId().equals(world.getUID())) {
                throw new IllegalArgumentException("Механизм и выходной контейнер должны быть в одном мире.");
            }
            var output = outputs.resolve(world.getBlockAt(anchor.x(), anchor.y(), anchor.z()));
            if (!output.equals(selected.output())) {
                throw new IllegalArgumentException("Выходной контейнер изменился. Выберите его заново.");
            }
            current.add(output);
        }
        var farm = registry.register(player.getUniqueId(), name, mechanism, current);
        selections.remove(player.getUniqueId());
        notice(player, "✔ Ферма зарегистрирована. Дальше: /bake profile (см. чат)", NamedTextColor.GREEN);
        player.sendMessage("Ферма зарегистрирована" + (name == null
                ? " без имени: указывайте её как " + farm.reference() + " или задайте имя через /bake rename."
                : " как " + name + "."));
        show(player, farm);
    }

    private static void show(Player player, FarmRegistry.MarkedFarm farm) {
        player.sendMessage("Ферма " + (farm.name() == null ? "без имени" : farm.name()) + " | " + farm.id() + " | "
                + farm.state() + " | " + coordinates(farm.mechanism().min()) + " → "
                + coordinates(farm.mechanism().max()));
        if (farm.profile() != null) {
            player.sendMessage("Профиль (предметов / 20 тиков, полезный участок " + farm.profile().usefulTicks()
                    + " тиков):");
        }
        for (int i = 0; i < farm.outputs().size(); i++) {
            var line = new StringBuilder("  Выходной контейнер №" + (i + 1) + ": "
                    + farm.outputs().get(i).describe());
            if (farm.profile() != null) {
                var rates = new StringBuilder();
                farm.profile().outputs().get(i).entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(rate -> rates.append(rates.isEmpty() ? "" : ", ")
                                .append(rate.getKey().getKey().getKey()).append(": ")
                                .append(String.format(Locale.ROOT, "%.4f", rate.getValue())));
                line.append(" | ").append(rates.isEmpty() ? "нет продукции" : rates);
            }
            player.sendMessage(line.toString());
        }
    }

    private static String coordinates(BlockPosition block) {
        return MarkingSelection.coordinates(block);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isWand(event.getPlayer().getInventory().getItemInMainHand())) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        selections.remove(event.getPlayer().getUniqueId());
        notices.remove(event.getPlayer().getUniqueId());
        lastClicks.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        selections.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) return List.of();
        if (args.length == 1) {
            var prefix = args[0].toLowerCase(Locale.ROOT);
            return COMMANDS.stream().filter(value -> value.startsWith(prefix))
                    .filter(value -> !value.equals("sprint") || player.hasPermission(TICK_PERMISSION)).toList();
        }
        var operation = args[0].toLowerCase(Locale.ROOT);
        if (!FARM_COMMANDS.contains(operation)) return List.of();
        if (operation.equals("sprint") && !player.hasPermission(TICK_PERMISSION)) return List.of();
        var farms = registry.findByCreator(player.getUniqueId());
        var reference = FarmArguments.complete(farms, args, 1);
        if (reference != null) return reference;
        int afterReference = args[1].equalsIgnoreCase(FarmArguments.ID_FLAG) ? 3 : 2;
        if (args.length == afterReference + 1 && args[0].equalsIgnoreCase("unregister")
                && CONFIRM.startsWith(args[afterReference].toLowerCase(Locale.ROOT))) {
            return List.of(CONFIRM);
        }
        return List.of();
    }
}
