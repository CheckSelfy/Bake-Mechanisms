package io.github.checkselfy.bakemechanisms.correctness;

import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.State;
import io.github.checkselfy.bakemechanisms.marking.OutputContainer;
import io.github.checkselfy.bakemechanisms.storage.FarmStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Packet-input acceptance on an isolated world; never calls Bukkit's event dispatcher. */
final class ClientAcceptanceReview {
    private final JavaPlugin plugin;
    private final Path control = Path.of("client-control");
    private final Queue<Runnable> actions = new ArrayDeque<>();
    private final FarmStorage storage;
    private Player a;
    private Player b;
    private World world;
    private UUID farm;
    private int sequence;
    private int waitUntil;
    private int actionsRun;
    private int actionStarted;
    private int movementMessageStart;
    private boolean initialized;
    private boolean reconnecting;
    private int reconnectStarted;
    private int resetMessageStart;

    ClientAcceptanceReview(JavaPlugin plugin) {
        this.plugin = plugin;
        storage = new FarmStorage(plugin.getServer().getPluginManager().getPlugin("BakeMechanisms")
                .getDataFolder().toPath().resolve("farms"));
    }

    void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            try {
                if (!initialized) {
                    a = plugin.getServer().getPlayer("ReviewCreator");
                    b = plugin.getServer().getPlayer("ReviewOther");
                    if (a == null || b == null) { a = null; return; }
                    require(!a.isOp() && !b.isOp(), "packet clients must be non-OP");
                    plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), "bakefixtures create correctness");
                    world = plugin.getServer().getWorld("bake_blocks");
                    initialized = true;
                    a.setInvulnerable(true); b.setInvulnerable(true);
                    plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), "tick rate 100");
                    plan(GameMode.SURVIVAL, 128);
                    plan(GameMode.CREATIVE, 176);
                    actions.add(() -> {
                        require(storage.loadAll().isEmpty(), "client review leaked registrations");
                        pass("CLIENT_ACCEPTANCE_PASSED", "wire commands/clicks/sneak/reach/container/movement/messages; survival and creative; no synthetic events");
                        plugin.getServer().shutdown();
                    });
                }
                if (reconnecting) {
                    var joined = plugin.getServer().getPlayer(aName());
                    if (joined == null) {
                        require(plugin.getServer().getCurrentTick() - reconnectStarted < 2000, "expected client did not reconnect");
                        return;
                    }
                    a = joined;
                    require(!a.isOp(), "reconnected creator became OP");
                    reconnecting = false;
                }
                if (plugin.getServer().getCurrentTick() < waitUntil) return;
                require(a.isOnline() && b.isOnline(), "packet client disconnected before acceptance completed");
                if (!actions.isEmpty()) {
                    if (actionStarted == 0) actionStarted = plugin.getServer().getCurrentTick();
                    try { actions.element().run(); }
                    catch (AssertionError pending) {
                        // Packet processing and client receipt are asynchronous. Keep the action
                        // pending for a bounded interval instead of assuming one network round trip.
                        if (plugin.getServer().getCurrentTick() - actionStarted < 400) return;
                        throw pending;
                    }
                    actions.remove(); actionsRun++; actionStarted = 0;
                }
            } catch (Throwable error) {
                plugin.getLogger().log(Level.SEVERE, "REVIEW_CORRECTNESS_FAILED client action=" + actionsRun, error);
                plugin.getServer().getScheduler().cancelTasks(plugin);
                plugin.getServer().shutdown();
            }
        }, 1L, 40L);
    }

    private void plan(GameMode mode, int x) {
        String name = "Client" + mode;
        boolean doubleChest = mode == GameMode.CREATIVE;
        actions.add(() -> {
            a.setGameMode(mode); b.setGameMode(GameMode.SURVIVAL);
            a.teleport(new Location(world, x + 1.5, 65, -1.5));
            b.teleport(new Location(world, x - 2.5, 65, 1.5));
            a.getInventory().clear(); b.getInventory().clear();
            for (int dx = -3; dx <= 42; dx++) for (int z = -3; z <= 4; z++) world.getBlockAt(x + dx, 63, z).setType(Material.STONE, false);
            world.getBlockAt(x, 64, 0).setType(Material.STONE, false);
            world.getBlockAt(x + 16, 66, 2).setType(Material.STONE, false);
            world.getBlockAt(x + 40, 66, 2).setType(Material.STONE, false);
            if (doubleChest) {
                world.getBlockAt(x + 15, 64, 3).setBlockData(plugin.getServer().createBlockData("minecraft:chest[facing=north,type=left]"), false);
                world.getBlockAt(x + 16, 64, 3).setBlockData(plugin.getServer().createBlockData("minecraft:chest[facing=north,type=right]"), false);
                require(output(x).getInventory().getSize() == 54, "native double chest was not paired");
            } else world.getBlockAt(x + 15, 64, 3).setType(Material.BARREL, false);
        });
        packet(aName(), () -> "command\tbake wand");
        packet(aName(), () -> "slot\t0");
        packet(aName(), () -> "left\t" + x + "\t64\t0");
        actions.add(() -> a.teleport(new Location(world, x + 15.5, 65, .5)));
        packet(aName(), () -> "right\t" + (x + 16) + "\t66\t2");
        packet(aName(), () -> "right\t" + (x + 40) + "\t66\t2");
        actions.add(() -> a.teleport(new Location(world, x + 15.5, 65, .5)));
        packet(aName(), () -> "sneak\ttrue");
        actions.add(() -> require(a.isSneaking(), "wire input did not enable sneaking"));
        packet(aName(), () -> "right\t" + (x + 15) + "\t64\t3");
        if (doubleChest) {
            packet(aName(), () -> "right\t" + (x + 16) + "\t64\t3");
            packet(aName(), () -> "right\t" + (x + 15) + "\t64\t3");
        }
        packet(aName(), () -> "sneak\tfalse");
        packet(aName(), () -> "command\tbake register " + name);
        actions.add(() -> {
            var found = storage.loadAll();
            require(found.size() == 1, "wire registration failed or duplicated: " + found);
            var registered = found.getFirst().farm(); farm = registered.id();
            require(registered.mechanism().min().x() == x && registered.mechanism().max().x() == x + 16,
                    "out-of-reach packet changed selection");
            require(registered.outputs().size() == 1 && registered.outputs().getFirst().blocks().size() == (doubleChest ? 2 : 1),
                    "double chest halves did not toggle one logical output");
            require(registered.outputs().getFirst().type() == (doubleChest ? OutputContainer.Type.DOUBLE_CHEST : OutputContainer.Type.BARREL), "wrong output type");
            require(world.getBlockAt(x, 64, 0).getType() == Material.STONE, "wand left click broke the mechanism");
            a.teleport(new Location(world, x + 15.5, 65, 5.5));
            pass("C01_WIRE_" + mode, "real input/reach/corners/output; cross-chunk double chest=" + doubleChest);
        });
        packet(bName(), () -> "command\tbake info --id " + farm);
        actions.add(() -> require(messages(b).contains("создател"), "noncreator refusal did not reach client"));
        selectionAttempt(x, 2, "Conflict" + mode);
        actions.add(() -> {
            require(storage.loadAll().size() == 1 && messages(a).contains("занят"), "conflicting wire registration was not refused");
            world.getBlockAt(x + 16, 66, 4).setType(Material.STONE, false);
        });
        selectionAttempt(x, 4, "Inside" + mode);
        actions.add(() -> {
            require(storage.loadAll().size() == 1 && messages(a).contains("вне механизма"), "wire registration accepted output inside mechanism");
            world.getBlockAt(x + 16, 66, 4).setType(Material.AIR, false);
            pass("C01_CONFLICTS_WIRE_" + mode, "overlapping registration and internal output refused without partial registration");
        });
        packet(aName(), () -> "command\tbake unselect");
        selectOnly(x, 2);
        actions.add(() -> {
            resetMessageStart = messages(a).length();
            a.teleport(new Location(plugin.getServer().getWorld("bake_entities"), -2.5, 65, 7.5));
            a.teleport(new Location(world, x + 15.5, 65, 5.5));
        });
        packet(aName(), () -> "command\tbake register ResetWorld" + mode);
        actions.add(() -> require(storage.loadAll().size() == 1 && messages(a).substring(resetMessageStart).contains("Выберите оба угла"),
                "world change retained wire selection"));
        selectOnly(x, 2);
        actions.add(() -> {
            resetMessageStart = messages(a).length();
            try { Files.writeString(control.resolve(aName() + ".reconnect"), "expected selection-reset test"); }
            catch (java.io.IOException error) { throw new IllegalStateException(error); }
            reconnecting = true;
            reconnectStarted = plugin.getServer().getCurrentTick();
            a.kickPlayer("Review: expected reconnect to verify selection reset");
        });
        packet(aName(), () -> "command\tbake register ResetLogout" + mode);
        actions.add(() -> {
            require(storage.loadAll().size() == 1 && messages(a).substring(resetMessageStart).contains("Выберите оба угла"),
                    "logout retained wire selection");
            pass("C01_RESETS_WIRE_" + mode, "real world changes and real disconnect/reconnect clear selection without changing registration");
        });
        for (String old : new String[] {"output", "clear", "cancel --id ", "restore --id ", "delete --id "}) {
            packet(aName(), () -> "command\tbake " + old + (old.endsWith(" ") ? farm : ""));
        }
        actions.add(() -> require(state() == State.NEEDS_PROFILING && messages(a).contains("переименована"), "legacy wire commands mutated farm or omitted rename message"));
        actions.add(() -> a.getInventory().clear());
        packet(aName(), () -> "right\t" + (x + 15) + "\t64\t3");
        actions.add(() -> require(a.getOpenInventory().getTopInventory().getSize() == (doubleChest ? 54 : 27), "wire right click did not open output"));
        actions.add(() -> a.closeInventory());
        packet(aName(), () -> "command\tbake profile --id " + farm);
        actions.add(() -> require(state() == State.PROFILING, "wire profile did not start"));
        actions.add(() -> movementMessageStart = messages(b).length());
        packet(bName(), () -> "move\t" + (x + .5) + "\t65\t1.5");
        actions.add(() -> require(b.getLocation().getX() < x && messages(b).substring(movementMessageStart).contains("область защищена"),
                "wire movement was not refused by profiling protection"));
        packet(bName(), () -> "left\t" + x + "\t64\t0");
        actions.add(() -> require(world.getBlockAt(x, 64, 0).getType() == Material.STONE, "wire break bypassed profiling protection"));
        packet(aName(), () -> "right\t" + (x + 15) + "\t64\t3");
        actions.add(() -> require(a.getOpenInventory().getTopInventory().getType() == org.bukkit.event.inventory.InventoryType.CRAFTING,
                "wire right click opened protected profiling output"));
        packet(aName(), () -> "command\tbake cancel-profiling --id " + farm);
        actions.add(() -> {
            require(state() == State.NEEDS_PROFILING, "wire cancel failed");
            require(messages(a).contains("отменено"), "cancel message not received by client");
        });
        packet(aName(), () -> "command\tbake profile --id " + farm);
        if (!doubleChest) {
            actions.add(() -> output(x).getInventory().addItem(new ItemStack(Material.STONE, 2)));
            actions.add(() -> output(x).getInventory().addItem(new ItemStack(Material.STONE, 2)));
        }
        actions.add(() -> {
            waitUntil = plugin.getServer().getCurrentTick() + 3600;
            // These small fixtures have controlled output, so run the actual server ticks quickly.
            // Natural-yield observation remains in the separate full correctness run.
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), "tick sprint 3600t");
        });
        actions.add(() -> {
            require(state() == (doubleChest ? State.NEEDS_PROFILING : State.READY), "wire profiling result mismatch");
            require(messages(a).contains(doubleChest ? "отклонён" : "READY"), "profiling result message not received by client");
            pass("C03_C04_WIRE_" + mode, "protected movement/break/open; cancellation and completed result messages received");
        });
        if (!doubleChest) {
            packet(aName(), () -> "command\tbake stasis --id " + farm);
            actions.add(() -> {
                require(state() == State.STASIS, "wire stasis failed");
                b.teleport(new Location(world, x - 2.5, 65, 1.5));
            });
            for (var breakMode : new GameMode[] {GameMode.SURVIVAL, GameMode.CREATIVE}) {
                actions.add(() -> b.setGameMode(breakMode));
                packet(bName(), () -> "left\t" + x + "\t64\t0");
                actions.add(() -> {
                    var region = storage.loadAll().stream().filter(f -> f.farm().id().equals(farm)).findFirst().orElseThrow().farm().mechanism();
                    var filler = new io.github.checkselfy.bakemechanisms.stasis.SlimeFiller(region,
                            io.github.checkselfy.bakemechanisms.stasis.FillerStyle.DEFAULT);
                    require(world.getBlockAt(x, 64, 0).getType() == filler.material(x-region.min().x(),64-region.min().y(),-region.min().z()),
                            "wire break damaged filler in " + breakMode);
                });
            }
            actions.add(() -> {
                world.getBlockAt(x + 16, 64, 3).setBlockData(plugin.getServer().createBlockData("minecraft:piston[facing=west,extended=false]"));
                world.getBlockAt(x + 17, 64, 3).setType(Material.REDSTONE_BLOCK);
            });
            actions.add(() -> {
                var piston = world.getBlockAt(x + 16, 64, 3);
                require(piston.isBlockPowered() || piston.isBlockIndirectlyPowered(), "native piston was not powered");
                require(world.getBlockAt(x + 15, 64, 3).getType() == Material.BARREL, "native piston moved stasis output");
                require(!((org.bukkit.block.data.type.Piston) world.getBlockAt(x + 16, 64, 3).getBlockData()).isExtended(),
                        "native piston extended through protected output");
                world.getBlockAt(x + 17, 64, 3).setType(Material.AIR);
                world.getBlockAt(x + 16, 64, 3).setType(Material.AIR);
                pass("C08_PISTON", "powered native piston could not move the stasis output; vanilla immovable-container behavior also applies");
            });
            actions.add(() -> b.teleport(new Location(world, x + 15.5, 65, 5.5)));
            packet(bName(), () -> "left\t" + (x + 15) + "\t64\t3");
            actions.add(() -> require(world.getBlockAt(x + 15, 64, 3).getType() == Material.BARREL, "creative wire break destroyed protected output"));
            actions.add(() -> output(x).getInventory().setItem(0, new ItemStack(Material.STONE, 64)));
            packet(aName(), () -> "right\t" + (x + 15) + "\t64\t3");
            actions.add(() -> require(a.getOpenInventory().getTopInventory().getSize() == 27, "stasis output is not accessible"));
            packet(aName(), () -> "shift_click\t0");
            actions.add(() -> {
                require(a.getInventory().containsAtLeast(new ItemStack(Material.STONE), 64), "wire inventory click did not extract a stack");
                pass("C08_PLAYER_WIRE", "native container shift-click moved a full stack to the non-OP player's inventory");
            });
            actions.add(() -> a.closeInventory());
            packet(aName(), () -> "command\tbake unstasis --id " + farm);
            actions.add(() -> {
                require(state() == State.NEEDS_PROFILING && world.getBlockAt(x, 64, 0).getType() == Material.STONE, "wire restore failed");
                require(messages(a).contains("восстановлена"), "restore message not received");
                pass("C05_WIRE", "real survival/creative break packets protected filler; stasis output opened; restore completed");
            });
        }
        packet(aName(), () -> "command\tbake unregister --id " + farm);
        packet(aName(), () -> "command\tbake unregister --id " + farm + " confirm");
        actions.add(() -> require(storage.loadAll().isEmpty(), "wire confirmed unregister failed"));
    }

    private Container output(int x) { return (Container) world.getBlockAt(x + 15, 64, 3).getState(); }
    private void selectionAttempt(int x, int maxZ, String name) {
        selectOnly(x, maxZ);
        packet(aName(), () -> "command\tbake register " + name);
    }
    private void selectOnly(int x, int maxZ) {
        packet(aName(), () -> "command\tbake unselect");
        actions.add(() -> a.teleport(new Location(world, x + 1.5, 65, -1.5)));
        packet(aName(), () -> "left\t" + x + "\t64\t0");
        actions.add(() -> a.teleport(new Location(world, x + 15.5, 65, maxZ - 1.5)));
        packet(aName(), () -> "right\t" + (x + 16) + "\t66\t" + maxZ);
        actions.add(() -> a.teleport(new Location(world, x + 15.5, 65, 5.5)));
        packet(aName(), () -> "sneak\ttrue");
        packet(aName(), () -> "right\t" + (x + 15) + "\t64\t3");
        packet(aName(), () -> "sneak\tfalse");
    }
    private String aName() { return "ReviewCreator"; }
    private String bName() { return "ReviewOther"; }
    private State state() { return storage.loadAll().stream().filter(f -> f.farm().id().equals(farm)).findFirst().orElseThrow().farm().state(); }
    private String messages(Player player) {
        try { return Files.readString(control.resolve(player.getName() + ".messages")); }
        catch (java.io.IOException error) { throw new IllegalStateException("missing client messages", error); }
    }
    private void packet(String player, Supplier<String> action) {
        actions.add(() -> {
            try {
                Files.createDirectories(control);
                var target = control.resolve(player + "-" + (++sequence) + ".request");
                var temp = control.resolve(player + ".tmp");
                Files.writeString(temp, action.get());
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.io.IOException error) { throw new IllegalStateException(error); }
        });
    }
    private void pass(String name, String detail) { plugin.getLogger().info("REVIEW_PASS " + name + " tick=" + plugin.getServer().getCurrentTick() + " " + detail); }
    private static void require(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
}
