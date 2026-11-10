package io.github.checkselfy.bakemechanisms.marking;

import io.github.checkselfy.bakemechanisms.profiling.ProfilingService;
import io.github.checkselfy.bakemechanisms.profiling.ProfilingSettings;
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
    private static final List<String> COMMANDS = List.of("wand", "output", "register", "list", "info", "profile", "cancel", "delete", "clear");
    private static final List<String> FARM_COMMANDS = List.of("info", "profile", "cancel", "delete");
    private static final String HELP =
            "/bake wand | output | register | list | info <UUID> | profile <UUID> | cancel <UUID> | delete <UUID> | clear";
    private static final String CONFIRM = "confirm";
    private final JavaPlugin plugin;
    private final FarmRegistry registry;
    private final PaperOutputResolver outputs;
    private final ProfilingService profiling;
    private final FarmRemoval removal;
    private final NamespacedKey wandKey;
    private final Map<UUID, Selection> selections = new HashMap<>();

    private record Selection(BlockPosition first, BlockPosition second,
                             BlockPosition outputAnchor, OutputContainer output) {
        private Selection() { this(null, null, null, null); }
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
            String operation = args[0].toLowerCase(Locale.ROOT);
            boolean confirmation = operation.equals("delete") && args.length == 3
                    && args[2].equalsIgnoreCase(CONFIRM);
            if (args.length != (FARM_COMMANDS.contains(operation) ? 2 : 1) && !confirmation) {
                player.sendMessage(HELP);
                return true;
            }
            switch (operation) {
                case "wand" -> giveWand(player);
                case "output" -> {
                    Block block = player.getTargetBlockExact(6);
                    if (block == null) throw new IllegalArgumentException("Посмотрите на выходной контейнер в пределах 6 блоков.");
                    selectOutput(player, block);
                }
                case "register" -> register(player);
                case "list" -> {
                    var farms = registry.findByCreator(player.getUniqueId());
                    if (farms.isEmpty()) player.sendMessage("У вас нет зарегистрированных ферм.");
                    for (var farm : farms) show(player, farm);
                }
                case "info" -> show(player, registry.requireCreator(parseId(args[1]), player.getUniqueId()));
                case "profile" -> {
                    var id = parseId(args[1]);
                    profiling.start(id, player.getUniqueId());
                    player.sendMessage("Профилирование запущено на " + ProfilingSettings.DURATION_TICKS
                            + " тиков. Область и выходной контейнер защищены до завершения.");
                    show(player, registry.requireCreator(id, player.getUniqueId()));
                }
                case "cancel" -> {
                    var id = parseId(args[1]);
                    profiling.cancel(id, player.getUniqueId());
                    player.sendMessage("Профилирование отменено, механизм не изменён.");
                    show(player, registry.requireCreator(id, player.getUniqueId()));
                }
                case "delete" -> {
                    var id = parseId(args[1]);
                    if (confirmation) {
                        removal.confirm(id, player.getUniqueId());
                        player.sendMessage("Регистрация фермы " + id + " удалена вместе с профилем и данными."
                                + " Механизм остался в мире без изменений.");
                    } else {
                        show(player, removal.request(id, player.getUniqueId()));
                        player.sendMessage("Удалить регистрацию, профиль и сохранённые данные фермы? Механизм в мире"
                                + " не изменится; неперенесённый остаток буфера выработки будет потерян. Подтвердите в"
                                + " течение " + FarmRemoval.CONFIRMATION_SECONDS + " секунд: /bake delete " + id
                                + " " + CONFIRM);
                    }
                }
                case "clear" -> {
                    selections.remove(player.getUniqueId());
                    player.sendMessage("Текущее выделение очищено.");
                }
                default -> player.sendMessage(HELP);
            }
        } catch (IllegalArgumentException error) {
            player.sendMessage(error.getMessage());
        }
        return true;
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
                    first ? old.second() : block, old.outputAnchor(), old.output()));
            player.sendMessage((first ? "Первый" : "Второй") + " угол: " + coordinates(block));
        } catch (IllegalArgumentException error) {
            player.sendMessage(error.getMessage());
        }
    }

    private void selectOutput(Player player, Block block) {
        var output = outputs.resolve(block);
        var old = selections.getOrDefault(player.getUniqueId(), new Selection());
        selections.put(player.getUniqueId(), new Selection(old.first(), old.second(),
                PaperOutputResolver.position(block), output));
        player.sendMessage("Выбран выходной контейнер: " + output.type() + ", блоков: " + output.blocks().size());
    }

    private void register(Player player) {
        var selection = selections.getOrDefault(player.getUniqueId(), new Selection());
        if (selection.first() == null || selection.second() == null || selection.output() == null) {
            throw new IllegalArgumentException("Выберите оба угла и выходной контейнер выделителем.");
        }
        var mechanism = new Cuboid(selection.first(), selection.second());
        var world = plugin.getServer().getWorld(mechanism.min().worldId());
        if (world == null || !world.getUID().equals(player.getWorld().getUID())) {
            throw new IllegalArgumentException("Мир выделения недоступен или вы находитесь в другом мире.");
        }
        if (mechanism.min().y() < world.getMinHeight() || mechanism.max().y() >= world.getMaxHeight()) {
            throw new IllegalArgumentException("Кубоид выходит за допустимую высоту мира.");
        }
        var anchor = selection.outputAnchor();
        if (!anchor.worldId().equals(world.getUID())) {
            throw new IllegalArgumentException("Механизм и выходной контейнер должны быть в одном мире.");
        }
        var current = outputs.resolve(world.getBlockAt(anchor.x(), anchor.y(), anchor.z()));
        if (!current.equals(selection.output())) {
            throw new IllegalArgumentException("Выходной контейнер изменился. Выберите его заново.");
        }
        var farm = registry.register(player.getUniqueId(), mechanism, current);
        selections.remove(player.getUniqueId());
        player.sendMessage("Ферма зарегистрирована. Данные пока хранятся до остановки сервера.");
        show(player, farm);
    }

    private static UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Укажите UUID фермы из /bake list.");
        }
    }

    private static void show(Player player, FarmRegistry.MarkedFarm farm) {
        player.sendMessage("Ферма " + farm.id() + " | " + farm.state() + " | "
                + coordinates(farm.mechanism().min()) + " → " + coordinates(farm.mechanism().max())
                + " | выход: " + farm.output().type());
        if (farm.profile() != null) {
            var rates = new StringBuilder();
            farm.profile().ratesPer20Ticks().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(rate -> rates.append(rates.isEmpty() ? "" : ", ")
                            .append(rate.getKey().getKey().getKey()).append(": ")
                            .append(String.format(Locale.ROOT, "%.4f", rate.getValue())));
            player.sendMessage("Профиль (предметов / 20 тиков, полезный участок " + farm.profile().usefulTicks()
                    + " тиков): " + rates);
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
        if (args.length == 2 && FARM_COMMANDS.contains(args[0].toLowerCase(Locale.ROOT))) {
            return registry.findByCreator(player.getUniqueId()).stream().map(farm -> farm.id().toString())
                    .filter(id -> id.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("delete")
                && CONFIRM.startsWith(args[2].toLowerCase(Locale.ROOT))) {
            return List.of(CONFIRM);
        }
        return List.of();
    }
}
