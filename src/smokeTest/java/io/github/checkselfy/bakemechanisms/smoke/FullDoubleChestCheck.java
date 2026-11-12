package io.github.checkselfy.bakemechanisms.smoke;

import io.github.checkselfy.bakemechanisms.emulation.EmulationSettings;
import io.github.checkselfy.bakemechanisms.emulation.EmulationState;
import io.github.checkselfy.bakemechanisms.emulation.EmulationStore;
import io.github.checkselfy.bakemechanisms.emulation.PaperEmulationWorld;
import io.github.checkselfy.bakemechanisms.emulation.StasisEmulationService;
import io.github.checkselfy.bakemechanisms.marking.BlockPosition;
import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.MarkedFarm;
import io.github.checkselfy.bakemechanisms.marking.OutputContainer;
import io.github.checkselfy.bakemechanisms.marking.PaperOutputResolver;
import io.github.checkselfy.bakemechanisms.profiling.ChunkTickets;
import io.github.checkselfy.bakemechanisms.profiling.GameTickGate;
import io.github.checkselfy.bakemechanisms.profiling.PaperProfilingWorld;
import io.github.checkselfy.bakemechanisms.profiling.ProductionProfile;
import io.github.checkselfy.bakemechanisms.stasis.FileSnapshotStore;
import io.github.checkselfy.bakemechanisms.stasis.PaperStasisWorld;
import io.github.checkselfy.bakemechanisms.stasis.StasisProduction;
import io.github.checkselfy.bakemechanisms.stasis.StasisService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

/**
 * #12 on real Paper: a completely full double chest stops gold1 production with a bounded buffer, and freeing it
 * resumes production without replaying the paused time or multiplying the buffered items.
 */
final class FullDoubleChestCheck {
    private static final int FULL_TICKS = 400;
    private static final int RESUMED_TICKS = 200;
    private final JavaPlugin plugin;
    private final World world;
    private final PaperOutputResolver resolver = new PaperOutputResolver();
    private final FarmRegistry registry = new FarmRegistry(FarmRegistry.DEFAULT_MAX_VOLUME);
    private final UUID creator = UUID.randomUUID();
    private final Map<UUID, EmulationState> saved = new java.util.HashMap<>();
    private StasisEmulationService emulation;
    private Runnable tick;
    private StasisService stasis;
    private MarkedFarm farm;
    private BukkitTask task;
    private int calls;
    private int largestBufferedFlesh;
    private int largestBufferedChicken;

    private FullDoubleChestCheck(JavaPlugin plugin) {
        this.plugin = plugin;
        this.world = plugin.getServer().getWorlds().getFirst();
    }

    static void run(JavaPlugin plugin, Consumer<Throwable> finish) {
        var check = new FullDoubleChestCheck(plugin);
        check.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            try {
                if (check.step()) {
                    check.cleanup();
                    finish.accept(null);
                }
            } catch (Throwable error) {
                check.cleanup();
                finish.accept(error);
            }
        }, 1L, 1L);
    }

    private boolean step() throws Exception {
        if (farm == null) start();
        tick.run();
        var state = saved.get(farm.id());
        if (state != null) {
            var buffer = state.outputs().getFirst().buffer();
            largestBufferedFlesh = Math.max(largestBufferedFlesh, buffer.getOrDefault(Material.ROTTEN_FLESH, 0));
            largestBufferedChicken = Math.max(largestBufferedChicken, buffer.getOrDefault(Material.CHICKEN, 0));
        }
        if (calls == FULL_TICKS) {
            var inventory = inventory();
            for (var item : inventory.getContents()) {
                check(item != null && item.getType() == Material.STONE && item.getAmount() == 64,
                        "a full double chest accepted production: " + item);
            }
            check(emulation.producing(farm.id()), "full output ended the stream");
            check(state != null && !state.outputs().getFirst().buffer().isEmpty(), "nothing waits for space");
            inventory.clear();
        } else if (calls == FULL_TICKS + RESUMED_TICKS) {
            verify();
            return true;
        }
        return false;
    }

    private void start() throws Exception {
        var paper = new PaperProfilingWorld(plugin, resolver);
        emulation = new StasisEmulationService(new PaperEmulationWorld(paper), new ChunkTickets(paper),
                EmulationSettings.DEFAULT, new EmulationStore() {
                    @Override public void saveEmulation(UUID farmId, EmulationState state) { saved.put(farmId, state); }
                    @Override public void saveDrain(UUID farmId, List<Map<Material, Integer>> buffers) { }
                    @Override public void deleteDrain(UUID farmId) { }
                });
        tick = GameTickGate.of(plugin.getServer().getServerTickManager(), () -> {
            emulation.tick();
            calls++;
        });
        int x = 432;
        int y = 300;
        int z = 32;
        // The smoke world persists between runs: empty the previous run's chest so replacing it drops nothing.
        for (int dx = 0; dx <= 3; dx++) for (int dy = 0; dy <= 2; dy++) for (int dz = 0; dz <= 2; dz++) {
            var block = world.getBlockAt(x + dx, y + dy, z + dz);
            if (block.getState(false) instanceof Container container) container.getInventory().clear();
            block.setType(dy == 0 ? Material.STONE : Material.AIR, false);
        }
        world.getNearbyEntities(new org.bukkit.util.BoundingBox(x - 4, y - 2, z - 4, x + 9, y + 3, z + 6)).stream()
                .filter(entity -> entity instanceof org.bukkit.entity.Item).forEach(org.bukkit.entity.Entity::remove);
        world.getBlockAt(x + 3, y, z).setBlockData(plugin.getServer()
                .createBlockData("minecraft:chest[facing=east,type=left,waterlogged=false]"), false);
        world.getBlockAt(x + 3, y, z + 1).setBlockData(plugin.getServer()
                .createBlockData("minecraft:chest[facing=east,type=right,waterlogged=false]"), false);
        var output = resolver.resolve(world.getBlockAt(x + 3, y, z));
        check(output.type() == OutputContainer.Type.DOUBLE_CHEST, "fixture output is not a double chest");
        var registered = registry.register(creator, new Cuboid(point(x, y, z), point(x + 2, y + 2, z + 2)), output);
        registry.markReady(registered.id(), new ProductionProfile(EmulationRateCheck.GOLD1, 3600));
        farm = registered;
        var inventory = inventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, new ItemStack(Material.STONE, 64));
        stasis = new StasisService(registry, new PaperStasisWorld(plugin, resolver),
                new FileSnapshotStore(plugin.getDataFolder().toPath().resolve("full-output-" + UUID.randomUUID()), "26.3"),
                new StasisProduction() {
                    @Override public void start(MarkedFarm stasisFarm) { emulation.start(stasisFarm, EmulationState.EMPTY); }
                    @Override public void pause(UUID farmId) { emulation.pause(farmId); }
                    @Override public void resume(UUID farmId) { emulation.resume(farmId); }
                    @Override public void finish(UUID farmId) { emulation.finish(farmId); }
                });
        stasis.enter(farm.id(), creator);
        farm = registry.requireCreator(farm.id(), creator);
    }

    private void verify() {
        // One update before the chest filled plus ten after it was freed; the 19 paused updates are not replayed.
        int updates = 1 + RESUMED_TICKS / 20;
        var expected = new EnumMap<Material, Integer>(Material.class);
        EmulationRateCheck.GOLD1.forEach((type, rate) -> {
            int whole = BigDecimal.valueOf(rate).multiply(BigDecimal.valueOf(updates))
                    .setScale(0, RoundingMode.FLOOR).intValueExact();
            if (whole > 0) expected.put(type, whole);
        });
        check(Map.copyOf(expected).equals(Map.of(Material.ROTTEN_FLESH, 27, Material.CHICKEN, 1)),
                "unexpected gold1 arithmetic: " + expected);
        var actual = new EnumMap<Material, Integer>(Material.class);
        for (var item : inventory().getContents()) {
            if (item != null && !item.getType().isAir()) actual.merge(item.getType(), item.getAmount(), Integer::sum);
        }
        check(actual.equals(expected), "after resuming: expected " + expected + ", resolved double chest holds " + actual);
        // Never more than one update's output waits: ceil(2.5028) rotten flesh and ceil(0.1006) chicken.
        check(largestBufferedFlesh <= 3 && largestBufferedChicken <= 1,
                "buffer exceeded one update: " + largestBufferedFlesh + " rotten flesh, " + largestBufferedChicken + " chicken");
        var anchor = farm.outputs().getFirst().blocks().iterator().next();
        check(world.getNearbyEntities(new BoundingBox(anchor.x() - 4, anchor.y() - 2, anchor.z() - 4,
                anchor.x() + 5, anchor.y() + 3, anchor.z() + 6)).stream().noneMatch(entity -> entity instanceof org.bukkit.entity.Item),
                "production was dropped into the world");
        plugin.getLogger().info("FULL_DOUBLE_CHEST_SMOKE_PASSED: 54 full slots for " + FULL_TICKS + " game ticks kept a buffer of at most "
                + largestBufferedFlesh + " rotten flesh / " + largestBufferedChicken + " chicken; " + RESUMED_TICKS
                + " ticks after freeing it the chest holds " + actual + " (no replay of the paused time)");
    }

    private DoubleChestInventory inventory() {
        var anchor = farm.outputs().getFirst().blocks().iterator().next();
        var state = (Container) world.getBlockAt(anchor.x(), anchor.y(), anchor.z()).getState(false);
        if (!(state.getInventory() instanceof DoubleChestInventory inventory)) throw new AssertionError("not a double chest");
        return inventory;
    }

    private void cleanup() {
        if (task != null) task.cancel();
        try {
            if (stasis != null && farm != null
                    && registry.requireCreator(farm.id(), creator).state() == FarmRegistry.State.STASIS) {
                stasis.restore(farm.id(), creator);
            }
        } catch (RuntimeException ignored) {
            // Cleanup only.
        }
        if (emulation != null) emulation.stopAll();
    }

    private BlockPosition point(int x, int y, int z) {
        return new BlockPosition(world.getUID(), x, y, z);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
