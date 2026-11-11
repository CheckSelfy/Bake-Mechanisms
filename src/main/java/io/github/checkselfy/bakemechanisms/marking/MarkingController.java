package io.github.checkselfy.bakemechanisms.marking;

import io.github.checkselfy.bakemechanisms.profiling.ProfilingService;
import io.github.checkselfy.bakemechanisms.profiling.ProfilingSettings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
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
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Command and wand boundary. All callbacks run on the server thread. */
public final class MarkingController implements Listener, TabExecutor {
    private static final List<String> COMMANDS = List.of("wand", "output", "register", "list", "info", "rename",
            "profile", "sprint", "cancel", "delete", "clear");
    private static final List<String> FARM_COMMANDS = List.of("info", "rename", "profile", "sprint", "cancel",
            "delete");
    private static final String FARM_USAGE = FarmArguments.USAGE;
    private static final String HELP = "/bake wand | output | register [имя] | list | info " + FARM_USAGE
            + " | rename " + FARM_USAGE + " <имя> | profile " + FARM_USAGE + " | sprint " + FARM_USAGE
            + " (ускоряет весь сервер) | cancel " + FARM_USAGE + " | delete " + FARM_USAGE + " [confirm] | clear";
    private static final String CONFIRM = "confirm";
    private final JavaPlugin plugin;
    private final FarmRegistry registry;
    private final PaperOutputResolver outputs;
    private final ProfilingService profiling;
    private final FarmRemoval removal;
    private final NamespacedKey wandKey;
    private final Map<UUID, Selection> selections = new HashMap<>();

    /** An output container chosen with the wand, with the block that was clicked to choose it. */
    private record SelectedOutput(BlockPosition anchor, OutputContainer output) { }

    private record Selection(BlockPosition first, BlockPosition second, List<SelectedOutput> outputs) {
        private Selection() { this(null, null, List.of()); }
    }

    public MarkingController(JavaPlugin plugin, FarmRegistry registry, PaperOutputResolver outputs,
                             ProfilingService profiling, FarmRemoval removal) {
        this.plugin = plugin;
        this.registry = registry;
        this.outputs = outputs;
        this.profiling = profiling;
        this.removal = removal;
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
                case "output" -> {
                    requireArguments(args, 1);
                    Block block = player.getTargetBlockExact(6);
                    if (block == null) throw new IllegalArgumentException("Посмотрите на выходной контейнер в пределах 6 блоков.");
                    selectOutput(player, block);
                }
                case "register" -> {
                    if (args.length > 2) throw new IllegalArgumentException("/bake register [имя]");
                    register(player, args.length == 2 ? args[1] : null);
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
                case "profile" -> {
                    var farm = farm(player, args, "/bake profile " + FARM_USAGE).farm();
                    profiling.start(farm.id(), actor);
                    player.sendMessage("Профилирование запущено на " + ProfilingSettings.DURATION_TICKS
                            + " тиков. Область и выходные контейнеры защищены до завершения. Ускорить: /bake sprint "
                            + farm.reference());
                    show(player, registry.requireCreator(farm.id(), actor));
                }
                case "sprint" -> sprint(player, farm(player, args, "/bake sprint " + FARM_USAGE).farm());
                case "cancel" -> {
                    var farm = farm(player, args, "/bake cancel " + FARM_USAGE).farm();
                    profiling.cancel(farm.id(), actor);
                    player.sendMessage("Профилирование отменено, механизм не изменён.");
                    show(player, registry.requireCreator(farm.id(), actor));
                }
                case "delete" -> {
                    var usage = "/bake delete " + FARM_USAGE + " [" + CONFIRM + "]";
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
                                + " течение " + FarmRemoval.CONFIRMATION_SECONDS + " секунд: /bake delete "
                                + farm.reference() + " " + CONFIRM);
                    } else {
                        throw new IllegalArgumentException(usage);
                    }
                }
                case "clear" -> {
                    requireArguments(args, 1);
                    selections.remove(actor);
                    player.sendMessage("Текущее выделение очищено.");
                }
                default -> player.sendMessage(HELP);
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
        player.sendMessage("Ускоряем сервер на " + remaining + " тиков до конца профилирования фермы "
                + farm.title() + ". Ускорение действует на весь сервер, включая другие профилируемые фермы.");
        player.performCommand("tick sprint " + remaining);
    }

    private void giveWand(Player player) {
        var wand = new ItemStack(Material.BLAZE_ROD);
        var meta = wand.getItemMeta();
        meta.displayName(Component.text("Выделитель Bake Mechanisms"));
        meta.lore(List.of(Component.text("ЛКМ/ПКМ — углы; Shift+ПКМ — выходной контейнер")));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        wand.setItemMeta(meta);
        if (!player.getInventory().addItem(wand).isEmpty()) {
            throw new IllegalArgumentException("Освободите место в инвентаре для выделителя.");
        }
        player.sendMessage("ЛКМ — первый угол, ПКМ — второй, Shift+ПКМ — выход. Затем /bake register.");
    }

    private boolean isWand(ItemStack item) {
        return item != null && item.getType() == Material.BLAZE_ROD && item.hasItemMeta()
                && Byte.valueOf((byte) 1).equals(item.getItemMeta().getPersistentDataContainer()
                .get(wandKey, PersistentDataType.BYTE));
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (!isWand(event.getItem())) return;
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) return;
        if (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        var player = event.getPlayer();
        try {
            if (event.getAction() == Action.RIGHT_CLICK_BLOCK && player.isSneaking()) {
                selectOutput(player, event.getClickedBlock());
                return;
            }
            var block = PaperOutputResolver.position(event.getClickedBlock());
            var old = selections.getOrDefault(player.getUniqueId(), new Selection());
            boolean first = event.getAction() == Action.LEFT_CLICK_BLOCK;
            var other = first ? old.second() : old.first();
            if (other != null && !other.worldId().equals(block.worldId())) {
                throw new IllegalArgumentException("Углы должны находиться в одном мире. Сброс: /bake clear.");
            }
            selections.put(player.getUniqueId(), new Selection(first ? block : old.first(),
                    first ? old.second() : block, old.outputs()));
            player.sendMessage((first ? "Первый" : "Второй") + " угол: " + coordinates(block));
        } catch (IllegalArgumentException error) {
            player.sendMessage(error.getMessage());
        }
    }

    /** Adds the container to the selection, or removes it when it is already selected. */
    private void selectOutput(Player player, Block block) {
        var output = outputs.resolve(block);
        var old = selections.getOrDefault(player.getUniqueId(), new Selection());
        var selected = new ArrayList<>(old.outputs());
        boolean removed = selected.removeIf(entry -> entry.output().equals(output));
        if (!removed) selected.add(new SelectedOutput(PaperOutputResolver.position(block), output));
        selections.put(player.getUniqueId(), new Selection(old.first(), old.second(), selected));
        player.sendMessage((removed ? "Выходной контейнер убран из выделения: " : "Выходной контейнер добавлен: ")
                + output.type() + ", блоков: " + output.blocks().size() + ". Выбрано выходных контейнеров: " + selected.size()
                + ". Повторный выбор контейнера убирает его.");
    }

    private void register(Player player, String name) {
        var selection = selections.getOrDefault(player.getUniqueId(), new Selection());
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
        return block.x() + ", " + block.y() + ", " + block.z();
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isWand(event.getPlayer().getInventory().getItemInMainHand())) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        selections.remove(event.getPlayer().getUniqueId());
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
            return COMMANDS.stream().filter(value -> value.startsWith(prefix)).toList();
        }
        if (!FARM_COMMANDS.contains(args[0].toLowerCase(Locale.ROOT))) return List.of();
        var farms = registry.findByCreator(player.getUniqueId());
        var reference = FarmArguments.complete(farms, args, 1);
        if (reference != null) return reference;
        int afterReference = args[1].equalsIgnoreCase(FarmArguments.ID_FLAG) ? 3 : 2;
        if (args.length == afterReference + 1 && args[0].equalsIgnoreCase("delete")
                && CONFIRM.startsWith(args[afterReference].toLowerCase(Locale.ROOT))) {
            return List.of(CONFIRM);
        }
        return List.of();
    }
}
