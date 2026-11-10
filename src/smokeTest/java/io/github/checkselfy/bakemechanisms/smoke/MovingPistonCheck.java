package io.github.checkselfy.bakemechanisms.smoke;

import io.github.checkselfy.bakemechanisms.marking.BlockPosition;
import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry;
import io.github.checkselfy.bakemechanisms.marking.PaperOutputResolver;
import io.github.checkselfy.bakemechanisms.profiling.ProductionProfile;
import io.github.checkselfy.bakemechanisms.stasis.FileSnapshotStore;
import io.github.checkselfy.bakemechanisms.stasis.PaperStasisWorld;
import io.github.checkselfy.bakemechanisms.stasis.StasisService;
import io.github.checkselfy.bakemechanisms.stasis.PistonInMotionException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Piston;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

/** Exercises real piston strokes: refuses in-motion capture, waits, then safely saves and restores. */
final class MovingPistonCheck {
    private record Case(boolean sticky, boolean retract) {}
    private static final List<Case> CASES = List.of(new Case(false, false), new Case(true, false),
            new Case(false, true), new Case(true, true));

    static void run(JavaPlugin plugin, Consumer<Throwable> done) {
        runCase(plugin, 0, done);
    }

    private static void runCase(JavaPlugin plugin, int index, Consumer<Throwable> done) {
        if (index == CASES.size()) {
            plugin.getServer().getWorlds().getFirst().removePluginChunkTicket(22, 0, plugin);
            plugin.getServer().getWorlds().getFirst().setChunkForceLoaded(22, 0, false);
            plugin.getLogger().info("MOVING_PISTON_STASIS_SMOKE_PASSED: " + CASES.size() + " deferred cases");
            done.accept(null);
            return;
        }
        var test = CASES.get(index);
        var world = plugin.getServer().getWorlds().getFirst();
        world.addPluginChunkTicket(22, 0, plugin);
        world.setChunkForceLoaded(22, 0, true);
        var region = new Cuboid(point(world, 352, 300, 0), point(world, 356, 302, 2));
        var bounds = new BoundingBox(351, 299, -1, 358, 304, 4);
        each(world, block -> block.setType(block.getY() == 300 ? Material.STONE : Material.AIR, false));
        for (var entity : world.getNearbyEntities(bounds)) entity.remove();
        var base = world.getBlockAt(353, 301, 1);
        base.setBlockData(plugin.getServer().createBlockData(test.sticky()
                ? "minecraft:sticky_piston[facing=east,extended=false]" : "minecraft:piston[facing=east,extended=false]"), false);
        world.getBlockAt(354, 301, 1).setType(Material.STONE, false);
        world.getBlockAt(352, 301, 1).setType(Material.REDSTONE_BLOCK, true);
        Runnable capture = () -> capture(plugin, index, test, world, region, bounds, done);
        if (test.retract()) {
            await(plugin, 20, () -> world.getBlockAt(355, 301, 1).getType() == Material.STONE && !hasMoving(world), () -> {
                world.getBlockAt(352, 301, 1).setType(Material.AIR, true);
                await(plugin, 20, () -> hasMoving(world), capture, "piston did not start retracting", done);
            }, "piston did not extend before retraction", done);
        } else {
            await(plugin, 20, () -> hasMoving(world), capture, "piston did not start extending", error -> {
                if (error != null) plugin.getLogger().warning("Piston fixture: base=" + base.getBlockData().getAsString()
                        + ", power=" + world.getBlockAt(352, 301, 1).getBlockData().getAsString()
                        + ", head=" + world.getBlockAt(354, 301, 1).getBlockData().getAsString()
                        + ", target=" + world.getBlockAt(355, 301, 1).getBlockData().getAsString());
                done.accept(error);
            });
        }
    }

    private static void capture(JavaPlugin plugin, int index, Case test, World world, Cuboid region,
                                BoundingBox bounds, Consumer<Throwable> done) {
        var moving = new java.util.ArrayList<Block>();
        each(world, block -> { if (block.getType() == Material.MOVING_PISTON) moving.add(block); });
        check(!moving.isEmpty(), "test did not catch a moving piston: " + test);
        var registry = new FarmRegistry(FarmRegistry.DEFAULT_MAX_VOLUME);
        var resolver = new PaperOutputResolver();
        var adapter = new PaperStasisWorld(plugin, resolver);
        var store = new FileSnapshotStore(plugin.getDataFolder().toPath().resolve(UUID.randomUUID().toString()), "26.3");
        var service = new StasisService(registry, adapter, store);
        var output = world.getBlockAt(384, 300, 0);
        output.setType(Material.BARREL, false);
        UUID creator = UUID.randomUUID();
        var farm = registry.register(creator, region, resolver.resolve(output));
        registry.markReady(farm.id(), new ProductionProfile(Map.of(Material.STONE, 1.0), 3600));
        try {
            service.enter(farm.id(), creator);
            throw new AssertionError("in-motion snapshot was accepted");
        } catch (PistonInMotionException expected) {
            check(moving.stream().allMatch(block -> block.getType() == Material.MOVING_PISTON), "refusal changed the moving blocks");
            try { check(store.loadActive().isEmpty(), "refusal published a snapshot"); }
            catch (java.io.IOException error) { throw new IllegalStateException(error); }
        }
        await(plugin, 10, () -> !hasMoving(world), () -> {
        service.enter(farm.id(), creator);
        each(world, block -> check(block.getType() == Material.BEDROCK, "piston replacement incomplete"));
        check(world.getNearbyEntities(bounds).isEmpty(), "piston replacement dropped items");
        later(plugin, 2, () -> {
            each(world, block -> check(block.getType() == Material.BEDROCK, "original piston resumed during stasis"));
            var recovered = new StasisService(new FarmRegistry(1), adapter, store);
            try {
                recovered.recover();
            } catch (java.io.IOException error) {
                throw new IllegalStateException(error);
            }
            recovered.restore(farm.id(), creator);
            later(plugin, 5, () -> {
                var base = world.getBlockAt(353, 301, 1);
                check(base.getType() == (test.sticky() ? Material.STICKY_PISTON : Material.PISTON), "piston base lost");
                check(((Piston) base.getBlockData()).isExtended() != test.retract(), "piston base state changed");
                if (test.retract()) {
                    check(world.getBlockAt(354, 301, 1).getType() == (test.sticky() ? Material.STONE : Material.AIR), "retracted block lost");
                    check(world.getBlockAt(355, 301, 1).getType() == (test.sticky() ? Material.AIR : Material.STONE), "retraction duplicated block");
                } else {
                    check(world.getBlockAt(354, 301, 1).getType() == Material.PISTON_HEAD, "extended head lost");
                    check(world.getBlockAt(355, 301, 1).getType() == Material.STONE, "pushed block lost");
                }
                each(world, block -> check(block.getType() != Material.MOVING_PISTON, "restored movement never completed"));
                check(world.getNearbyEntities(bounds).isEmpty(), "piston restoration dropped duplicate items");
                runCase(plugin, index + 1, done);
            }, done);
        }, done);
        }, "piston did not settle before safe snapshot", done);
    }

    private static void later(JavaPlugin plugin, long ticks, Runnable action, Consumer<Throwable> done) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            try { action.run(); } catch (Throwable error) { done.accept(error); }
        }, ticks);
    }

    private static void await(JavaPlugin plugin, int remaining, BooleanSupplier ready, Runnable action,
                              String message, Consumer<Throwable> done) {
        if (ready.getAsBoolean()) { action.run(); return; }
        check(remaining > 0, message);
        later(plugin, 1, () -> await(plugin, remaining - 1, ready, action, message, done), done);
    }

    private static boolean hasMoving(World world) {
        boolean[] moving = { false };
        each(world, block -> { if (block.getType() == Material.MOVING_PISTON) moving[0] = true; });
        return moving[0];
    }

    private static void each(World world, Consumer<Block> action) {
        for (int x = 352; x <= 356; x++) for (int y = 300; y <= 302; y++) for (int z = 0; z <= 2; z++) {
            action.accept(world.getBlockAt(x, y, z));
        }
    }

    private static BlockPosition point(World world, int x, int y, int z) {
        return new BlockPosition(world.getUID(), x, y, z);
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
