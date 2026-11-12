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

/**
 * #12 regression on real Paper: stasis production of the benchmark's {@code gold1} profile into a double chest,
 * counted through the resolved 54-slot inventory, across a {@code /tick freeze} and then exactly 61 game ticks.
 *
 * <p>One farm is ticked through {@link GameTickGate} like the plugin; a second identical farm is ticked on every
 * scheduler tick, as the plugin did before the fix. Paper keeps running scheduler tasks while the worlds are
 * frozen, so the second farm reproduces the reported mismatch (far more than ~7.6 rotten flesh in 61 game ticks)
 * while the first produces exactly the profile rate times the elapsed game ticks.
 */
final class EmulationRateCheck {
    /** Rates of the {@code gold1} profile persisted during the 2026-11-12 benchmark, items per 20 ticks. */
    static final Map<Material, Double> GOLD1 = Map.of(
            Material.ROTTEN_FLESH, 2.5027932960893855, Material.CHICKEN, 0.1005586592178771);
    private static final int FROZEN_SERVER_TICKS = 200;
    private static final int MEASURED_GAME_TICKS = 61;

    private final JavaPlugin plugin;
    private final World world;
    private final PaperOutputResolver resolver = new PaperOutputResolver();
    private final FarmRegistry registry = new FarmRegistry(FarmRegistry.DEFAULT_MAX_VOLUME);
    private final UUID creator = UUID.randomUUID();
    private StasisEmulationService gated;
    private StasisEmulationService ungated;
    private Runnable gatedTick;
    private MarkedFarm gatedFarm;
    private MarkedFarm ungatedFarm;
    private StasisService stasis;
    private BukkitTask task;
    private long startGameTime;
    private int serverTicks;
    private int gatedCalls;
    private int ungatedCalls;

    private EmulationRateCheck(JavaPlugin plugin) {
        this.plugin = plugin;
        this.world = plugin.getServer().getWorlds().getFirst();
    }

    static void run(JavaPlugin plugin, Consumer<Throwable> finish) {
        var check = new EmulationRateCheck(plugin);
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

    /** @return whether the check finished successfully */
    private boolean step() throws Exception {
        var tickManager = plugin.getServer().getServerTickManager();
        if (stasis == null) {
            start();
            tickManager.setFrozen(true);
        } else {
            serverTicks++;
            long gameTicks = world.getGameTime() - startGameTime;
            if (serverTicks <= FROZEN_SERVER_TICKS) {
                // The first scheduler tick after the freeze request still ran its world tick.
                check(gameTicks <= 1, "world game time advanced while frozen: " + gameTicks);
                if (serverTicks == FROZEN_SERVER_TICKS) tickManager.setFrozen(false);
            } else if (gameTicks == MEASURED_GAME_TICKS) {
                verify(gameTicks);
                return true;
            } else {
                check(gameTicks < MEASURED_GAME_TICKS, "missed the measurement tick: " + gameTicks);
            }
        }
        // Checked before this tick's production: every counted call so far was followed by its world tick.
        gatedTick.run();
        ungated.tick();
        ungatedCalls++;
        return false;
    }

    private void start() throws Exception {
        gated = service();
        ungated = service();
        gatedTick = GameTickGate.of(plugin.getServer().getServerTickManager(), () -> {
            gated.tick();
            gatedCalls++;
        });
        gatedFarm = farm(400, gated);
        ungatedFarm = farm(416, ungated);
        // Both start producing in this scheduler tick from empty, resolved double chests.
        clear(gatedFarm);
        clear(ungatedFarm);
        stasis.enter(gatedFarm.id(), creator);
        stasis.enter(ungatedFarm.id(), creator);
        startGameTime = world.getGameTime();
    }

    private StasisEmulationService service() {
        var paper = new PaperProfilingWorld(plugin, resolver);
        return new StasisEmulationService(new PaperEmulationWorld(paper), new ChunkTickets(paper),
                EmulationSettings.DEFAULT, new EmulationStore() {
                    @Override public void saveEmulation(UUID farmId, EmulationState state) { }
                    @Override public void saveDrain(UUID farmId, List<Map<Material, Integer>> buffers) { }
                    @Override public void deleteDrain(UUID farmId) { }
                });
    }

    private MarkedFarm farm(int x, StasisEmulationService emulation) throws Exception {
        int y = 300;
        int z = 32;
        for (int dx = 0; dx <= 3; dx++) for (int dy = 0; dy <= 2; dy++) for (int dz = 0; dz <= 2; dz++) {
            world.getBlockAt(x + dx, y + dy, z + dz).setType(dy == 0 ? Material.STONE : Material.AIR, false);
        }
        // Output outside the mechanism: a real double chest, never accounted for through one half.
        world.getBlockAt(x + 3, y, z).setBlockData(plugin.getServer()
                .createBlockData("minecraft:chest[facing=east,type=left,waterlogged=false]"), false);
        world.getBlockAt(x + 3, y, z + 1).setBlockData(plugin.getServer()
                .createBlockData("minecraft:chest[facing=east,type=right,waterlogged=false]"), false);
        var output = resolver.resolve(world.getBlockAt(x + 3, y, z));
        check(output.type() == OutputContainer.Type.DOUBLE_CHEST, "fixture output is not a double chest: " + output);
        var mechanism = new Cuboid(point(x, y, z), point(x + 2, y + 2, z + 2));
        var farm = registry.register(creator, mechanism, output);
        registry.markReady(farm.id(), new ProductionProfile(GOLD1, 3600));
        if (stasis == null) {
            stasis = new StasisService(registry, new PaperStasisWorld(plugin, resolver),
                    new FileSnapshotStore(plugin.getDataFolder().toPath().resolve("emulation-rate-" + UUID.randomUUID()), "26.3"),
                    new Production());
        }
        production.put(farm.id(), emulation);
        return registry.requireCreator(farm.id(), creator);
    }

    private final Map<UUID, StasisEmulationService> production = new java.util.HashMap<>();

    /** Routes each farm to its own emulation service, as the plugin routes all farms to one. */
    private final class Production implements StasisProduction {
        @Override public void start(MarkedFarm farm) { production.get(farm.id()).start(farm, EmulationState.EMPTY); }
        @Override public void pause(UUID farmId) { production.get(farmId).pause(farmId); }
        @Override public void resume(UUID farmId) { production.get(farmId).resume(farmId); }
        @Override public void finish(UUID farmId) { production.get(farmId).finish(farmId); }
    }

    private void verify(long gameTicks) {
        check(gatedCalls == gameTicks, "gated production ran " + gatedCalls + " times in " + gameTicks + " game ticks");
        check(ungatedCalls == serverTicks, "ungated production missed scheduler ticks");
        var expected = expected(gatedCalls);
        // The persisted gold1 profile: 61 game ticks contain three 20-tick updates, 7.508 rotten flesh and 0.302 chicken.
        check(expected.equals(Map.of(Material.ROTTEN_FLESH, 7)), "unexpected gold1 arithmetic: " + expected);
        var actual = count(gatedFarm);
        check(actual.equals(expected), "gold1 production after " + gameTicks + " game ticks: expected " + expected
                + ", resolved double chest holds " + actual);
        var reproduced = count(ungatedFarm);
        check(reproduced.equals(expected(ungatedCalls)), "ungated production differs from scheduler ticks: " + reproduced);
        check(reproduced.getOrDefault(Material.ROTTEN_FLESH, 0) > actual.get(Material.ROTTEN_FLESH),
                "frozen scheduler ticks did not reproduce the over-production: " + reproduced);
        plugin.getLogger().info("EMULATION_RATE_SMOKE_PASSED: gold1 over " + gameTicks + " game ticks after "
                + FROZEN_SERVER_TICKS + " frozen server ticks = " + actual + " in the resolved double chest; counting "
                + "scheduler ticks (pre-fix) gives " + reproduced);
    }

    /** Whole items of {@code calls} ticks of gold1 production started from an empty state, updated every 20 ticks. */
    private static Map<Material, Integer> expected(int calls) {
        long producedTicks = (long) calls / 20 * 20;
        var result = new EnumMap<Material, Integer>(Material.class);
        GOLD1.forEach((type, rate) -> {
            // One update adds rate * 20 / 20 exactly; the sum of the updates' exact decimals floors to the items.
            int whole = BigDecimal.valueOf(rate).multiply(BigDecimal.valueOf(producedTicks / 20))
                    .setScale(0, RoundingMode.FLOOR).intValueExact();
            if (whole > 0) result.put(type, whole);
        });
        return Map.copyOf(result);
    }

    private void clear(MarkedFarm farm) {
        inventory(farm).clear();
    }

    private Map<Material, Integer> count(MarkedFarm farm) {
        var result = new EnumMap<Material, Integer>(Material.class);
        for (ItemStack item : inventory(farm).getContents()) {
            if (item != null && !item.getType().isAir()) result.merge(item.getType(), item.getAmount(), Integer::sum);
        }
        return Map.copyOf(result);
    }

    private DoubleChestInventory inventory(MarkedFarm farm) {
        var anchor = farm.outputs().getFirst().blocks().iterator().next();
        var block = world.getBlockAt(anchor.x(), anchor.y(), anchor.z());
        if (!(((Container) block.getState(false)).getInventory() instanceof DoubleChestInventory inventory)) {
            throw new AssertionError("output is no longer a double chest");
        }
        check(inventory.getSize() == 54, "double chest inventory is not 54 slots");
        return inventory;
    }

    private void cleanup() {
        if (task != null) task.cancel();
        plugin.getServer().getServerTickManager().setFrozen(false);
        for (var farm : new MarkedFarm[] { gatedFarm, ungatedFarm }) {
            if (farm == null) continue;
            try {
                if (registry.requireCreator(farm.id(), creator).state() == FarmRegistry.State.STASIS) {
                    stasis.restore(farm.id(), creator);
                }
            } catch (RuntimeException ignored) {
                // Cleanup only; the verdict is already decided.
            }
        }
        if (gated != null) gated.stopAll();
        if (ungated != null) ungated.stopAll();
    }

    private BlockPosition point(int x, int y, int z) {
        return new BlockPosition(world.getUID(), x, y, z);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
