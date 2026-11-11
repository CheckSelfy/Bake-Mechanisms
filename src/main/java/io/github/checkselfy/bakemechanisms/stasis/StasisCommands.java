package io.github.checkselfy.bakemechanisms.stasis;

import io.github.checkselfy.bakemechanisms.marking.FarmArguments;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** Adds stasis operations without changing the marking/profiling command implementation. */
public final class StasisCommands implements TabExecutor {
    private static final int PISTON_WAIT_TICKS = 10;
    private final TabExecutor otherCommands;
    private final FarmRegistry registry;
    private final StasisService stasis;
    private final Logger logger;
    private final Consumer<Runnable> nextTick;
    private final Set<UUID> pending = new HashSet<>();

    public StasisCommands(TabExecutor otherCommands, FarmRegistry registry, StasisService stasis, Logger logger) {
        this(otherCommands, registry, stasis, logger, null);
    }

    public StasisCommands(TabExecutor otherCommands, FarmRegistry registry, StasisService stasis, Logger logger,
                          Consumer<Runnable> nextTick) {
        this.otherCommands = otherCommands;
        this.registry = registry;
        this.stasis = stasis;
        this.logger = logger;
        this.nextTick = nextTick;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) sender.sendMessage("/bake stasis " + FarmArguments.USAGE + " | restore " + FarmArguments.USAGE);
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
                if (pending.contains(id)) {
                    player.sendMessage("Стазис уже ожидает завершения хода поршней.");
                } else {
                    enterWhenStable(id, player, PISTON_WAIT_TICKS);
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

    private void enterWhenStable(UUID id, Player player, int retries) {
        try {
            stasis.enter(id, player.getUniqueId());
            pending.remove(id);
            player.sendMessage("Ферма " + registry.requireCreator(id, player.getUniqueId()).title()
                    + ": STASIS. Механизм сохранён и заменён заполнителем.");
        } catch (PistonInMotionException moving) {
            if (nextTick == null || retries == 0) {
                pending.remove(id);
                player.sendMessage(moving.getMessage());
                return;
            }
            if (pending.add(id)) player.sendMessage("Ждём завершения хода поршней перед стазисом…");
            nextTick.accept(() -> enterWhenStable(id, player, retries - 1));
        } catch (IllegalArgumentException error) {
            pending.remove(id);
            player.sendMessage(error.getMessage());
            if (error.getCause() != null) logger.log(Level.WARNING, "Stasis operation failed for " + id, error);
        }
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) return List.of();
        if (args.length == 1) {
            var result = new ArrayList<String>();
            var others = otherCommands.onTabComplete(sender, command, alias, args);
            if (others != null) result.addAll(others);
            for (var operation : List.of("stasis", "restore")) {
                if (operation.startsWith(args[0].toLowerCase(Locale.ROOT))) result.add(operation);
            }
            return List.copyOf(result);
        }
        if (isStasisCommand(args[0])) {
            var reference = FarmArguments.complete(registry.findByCreator(player.getUniqueId()), args, 1);
            return reference == null ? List.of() : reference;
        }
        return otherCommands.onTabComplete(sender, command, alias, args);
    }

    private static boolean isStasisCommand(String value) {
        return value.equalsIgnoreCase("stasis") || value.equalsIgnoreCase("restore");
    }
}
