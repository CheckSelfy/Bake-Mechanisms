package io.github.checkselfy.bakemechanisms.stasis;

import io.github.checkselfy.bakemechanisms.marking.FarmArguments;
import io.github.checkselfy.bakemechanisms.marking.FarmOperations;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** Adds stasis operations without changing the marking/profiling command implementation. */
public final class StasisCommands implements TabExecutor {
    private final TabExecutor otherCommands;
    private final FarmRegistry registry;
    private final StasisService stasis;
    private final Logger logger;
    private final StasisStabilizer stabilizer;

    public StasisCommands(TabExecutor otherCommands, FarmRegistry registry, StasisService stasis, Logger logger) {
        this(otherCommands, registry, stasis, logger, null);
    }

    public StasisCommands(TabExecutor otherCommands, FarmRegistry registry, StasisService stasis, Logger logger,
                          Consumer<Runnable> nextTick) {
        this.otherCommands = otherCommands;
        this.registry = registry;
        this.stasis = stasis;
        this.logger = logger;
        this.stabilizer = new StasisStabilizer(stasis, nextTick);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) sender.sendMessage("/bake stasis " + FarmArguments.USAGE + " | unstasis " + FarmArguments.USAGE);
        if (args.length == 0 || !isStasisCommand(args[0])) {
            return otherCommands.onCommand(sender, command, label, args);
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Команды стазиса доступны только игрокам.");
            return true;
        }
        var usage = "/bake " + args[0].toLowerCase(Locale.ROOT) + " " + FarmArguments.USAGE;
        UUID id = null;
        try {
            if (args.length < 2) throw new IllegalArgumentException(usage);
            var parsed = FarmArguments.parse(args, 1);
            if (!parsed.rest().isEmpty()) throw new IllegalArgumentException(usage);
            // Ownership is checked on every request, even a duplicate while a retry is pending.
            var farm = registry.require(parsed.farm(), player.getUniqueId());
            id = farm.id();
            if (args[0].equalsIgnoreCase("stasis")) {
                if (stabilizer.pending(id)) {
                    player.sendMessage("Стазис фермы уже ожидает стабилизации механизма.");
                } else {
                    enterWhenStable(id, player);
                }
            } else {
                stasis.restore(id, player.getUniqueId());
                player.sendMessage("Ферма " + farm.title()
                        + " восстановлена: NEEDS_PROFILING. Требуется новое профилирование.");
            }
        } catch (IllegalArgumentException error) {
            player.sendMessage(error.getMessage());
            if (error.getCause() != null) logger.log(Level.WARNING, "Stasis operation failed for " + id, error);
        }
        return true;
    }

    /**
     * Pistons in motion and entities being spawned, killed, mounted or dismounted are waited out automatically.
     * The player sees why in chat (once per distinct reason) and a live countdown in the action bar.
     */
    private void enterWhenStable(UUID id, Player player) {
        var title = registry.requireCreator(id, player.getUniqueId()).title();
        stabilizer.enter(id, player.getUniqueId(), new StasisStabilizer.Listener() {
            private String lastReason;
            private int waited;

            @Override public void waiting(TransientStasisException reason, int retry, int retriesLeft) {
                waited = retry;
                if (!reason.getMessage().equals(lastReason)) {
                    lastReason = reason.getMessage();
                    player.sendMessage(retry == 1
                            ? "Ферма " + title + ": " + reason.getMessage() + " Повторяем стазис автоматически…"
                            : "Ферма " + title + ", стабилизация продолжается: " + reason.getMessage());
                }
                player.sendActionBar(Component.text("Стабилизация фермы " + title + " перед стазисом: попытка "
                        + (retry + 1) + ", осталось до " + seconds(retriesLeft + 1) + " с", NamedTextColor.YELLOW));
            }

            @Override public void entered() {
                player.sendMessage("Ферма " + title + ": STASIS. Механизм сохранён и заменён заполнителем."
                        + (waited == 0 ? "" : " Стабилизация заняла " + waited + " тик(ов)."));
                if (waited > 0) player.sendActionBar(Component.text("Ферма " + title + ": STASIS", NamedTextColor.GREEN));
            }

            @Override public void failed(IllegalArgumentException error) {
                if (waited > 0 && error instanceof TransientStasisException) {
                    player.sendMessage("Ферма " + title + " не стабилизировалась за " + seconds(waited)
                            + " с. Последняя причина: " + error.getMessage() + " Повторите /bake stasis позже.");
                    player.sendActionBar(Component.text("Ферма " + title + ": стазис не выполнен", NamedTextColor.RED));
                } else {
                    player.sendMessage(error.getMessage());
                }
                if (error.getCause() != null) logger.log(Level.WARNING, "Stasis operation failed for " + id, error);
            }
        });
    }

    private static String seconds(int ticks) {
        return String.format(Locale.ROOT, "%.1f", ticks / 20.0);
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) return List.of();
        if (args.length == 1) {
            var result = new ArrayList<String>();
            var others = otherCommands.onTabComplete(sender, command, alias, args);
            if (others != null) result.addAll(others);
            for (var operation : List.of("stasis", "unstasis")) {
                if (operation.startsWith(args[0].toLowerCase(Locale.ROOT))) result.add(operation);
            }
            return List.copyOf(result);
        }
        if (isStasisCommand(args[0])) {
            var farms = FarmOperations.eligible(args[0], registry.findByCreator(player.getUniqueId()));
            var reference = FarmArguments.complete(farms, args, 1);
            return reference == null ? List.of() : reference;
        }
        return otherCommands.onTabComplete(sender, command, alias, args);
    }

    private static boolean isStasisCommand(String value) {
        return value.equalsIgnoreCase("stasis") || value.equalsIgnoreCase("unstasis");
    }
}
