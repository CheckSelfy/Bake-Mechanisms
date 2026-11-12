package io.github.checkselfy.bakemechanisms.lab;

import io.github.checkselfy.bakemechanisms.emulation.EmulationState;
import io.github.checkselfy.bakemechanisms.marking.BlockPosition;
import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.profiling.ProfilingIsolation;
import io.github.checkselfy.bakemechanisms.storage.FarmStorage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

/**
 * Headless review of the three-farm zombified-piglin benchmark (#12), driven by a real non-OP client through the
 * production command contract. Runs in two server processes around {@code gradlew resetFarmLab}:
 *
 * <ol>
 *   <li>first process: registers all three farms, then stops (they become stale lab registrations);</li>
 *   <li>after the reset, second process: no stale registration survives and all three register again; then
 *       (Stream C smoke) all three are profiled in parallel from the safe AFK pad and enter stasis automatically;
 *       and (Stream D acceptance) original and stasis phases are benchmarked from the same position, production
 *       over exactly 1,200 game ticks is checked against the profile and the stored fractions, and the farms are
 *       restored.</li>
 * </ol>
 *
 * Any {@code /tick freeze}/{@code step}, {@code /kill}, persistence or dismount command fails the review.
 */
public final class MobPerformanceReviewPlugin extends JavaPlugin implements Listener {
    private static final int UPDATES = 60; // 1,200 game ticks of 20-tick emulation updates
    private FarmLabPlugin lab;
    private FarmStorage storage;
    private Player creator;
    private World world;
    private String mode;
    private int phase;
    private int ticks;
    private long phaseGameTime;
    private final Map<String, UUID> ids = new LinkedHashMap<>();
    private final Map<String, EmulationState> windowStart = new HashMap<>();
    private final List<String> forbidden = new ArrayList<>();
    private final YamlConfiguration result = new YamlConfiguration();
    private Path originalBenchmark;
    private Path stasisBenchmark;
    private boolean waiting;

    @Override public void onEnable() {
        lab = (FarmLabPlugin) getServer().getPluginManager().getPlugin("BakeFarmLab");
        storage = new FarmStorage(getServer().getPluginManager().getPlugin("BakeMechanisms").getDataFolder().toPath().resolve("farms"));
        // Written by scripts/farm-lab.py mob-review next to paper.jar (the working directory).
        var modeFile = Path.of("mob-review-mode.txt");
        try {
            mode = Files.exists(modeFile) ? Files.readString(modeFile).strip() : System.getProperty("bake.mob-review.mode", "smoke");
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot read the review mode", error);
        }
        check(mode.equals("smoke") || mode.equals("acceptance"), "unknown review mode " + mode);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, () -> {
            try { tick(); }
            catch (Throwable error) {
                getLogger().log(Level.SEVERE, "MOB_REVIEW_FAILED phase=" + phase, error);
                phase = 99;
                getServer().shutdown();
            }
        }, 20L, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) { audit(event.getPlayer().getName(), event.getMessage()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerCommand(ServerCommandEvent event) { audit("console", event.getCommand()); }

    /** Operator workarounds that #12's acceptance forbids; any use is recorded and fails the review. */
    private void audit(String sender, String raw) {
        var command = raw.toLowerCase(Locale.ROOT).replaceFirst("^/", "").replace("minecraft:", "");
        for (var part : command.split(" run ")) {
            part = part.strip();
            if (part.startsWith("tick freeze") || part.startsWith("tick step") || part.startsWith("kill")
                    || part.startsWith("data ") && part.contains("persist") || part.startsWith("ride ") && part.contains("dismount")) {
                forbidden.add(sender + ": " + raw);
            }
        }
    }

    private void tick() throws Exception {
        ticks++;
        check(forbidden.isEmpty(), "forbidden operator command used: " + forbidden);
        if (waiting) return;
        var seeded = getDataFolder().toPath().resolve("seeded.yml");
        if (phase == 0) {
            creator = getServer().getPlayerExact("ReviewCreator");
            if (creator == null) { check(ticks < 2400, "client did not join"); return; }
            check(!creator.isOp(), "the client must be non-OP");
            world = lab.mobPerformanceWorld();
            creator.setInvulnerable(true);
            check(creator.performCommand("farmlab visit mobperf01"), "farmlab visit failed");
            next(1);
        } else if (phase == 1) {
            if (ticks < 20) return;
            standsOnSafePad();
            if (!Files.exists(seeded)) {
                // First process: these registrations become stale when the lab is reset.
                for (var farm : lab.mobPerformance().benchmarkFarmGeometry()) register(farm);
                var record = new YamlConfiguration();
                record.set("world", world.getUID().toString());
                record.set("farms", new ArrayList<>(ids.values().stream().map(UUID::toString).toList()));
                Files.createDirectories(seeded.getParent());
                record.save(seeded.toFile());
                pass("SEEDED", "registered " + ids.keySet() + " in lab world " + world.getUID() + "; stopping for resetFarmLab");
                phase = 99;
                getServer().shutdown();
                return;
            }
            var previous = YamlConfiguration.loadConfiguration(seeded.toFile());
            check(!previous.getString("world").equals(world.getUID().toString()), "the lab world was not regenerated by the reset");
            var stale = storage.loadAll().stream().filter(stored -> previous.getStringList("farms").contains(stored.farm().id().toString())
                    || !stored.farm().mechanism().min().worldId().equals(world.getUID())).toList();
            check(stale.isEmpty(), "stale registrations survived resetFarmLab: " + stale.size());
            for (var farm : lab.mobPerformance().benchmarkFarmGeometry()) register(farm);
            check(storage.loadAll().size() == 3, "expected exactly three registrations after reset");
            pass("REGISTERED_AFTER_RESET", "stale=0 registered=" + ids.keySet());
            next(2);
        } else if (phase == 2) {
            // Native originals warm up, then (acceptance) the original phase is benchmarked from the pad.
            if (ticks < 600) return;
            standsOnSafePad();
            if (mode.equals("acceptance") && originalBenchmark == null) {
                benchmark("original", true, path -> originalBenchmark = path);
                return;
            }
            for (var farm : ids.values()) check(creator.performCommand("bake profile --id " + farm), "profile command rejected");
            for (var farm : ids.values()) check(state(farm).equals("PROFILING"), "profiling did not start for " + farm + ": " + state(farm));
            pass("PROFILING_STARTED", "three farms in parallel from the safe AFK pad");
            next(3);
        } else if (phase == 3) {
            if (ticks % 600 == 0) pass("PROFILING", "server ticks=" + ticks + " states=" + states());
            if (ids.values().stream().anyMatch(farm -> state(farm).equals("PROFILING"))) {
                check(ticks < 36000, "profiling did not finish");
                return;
            }
            for (var entry : ids.entrySet()) {
                var stored = stored(entry.getValue());
                check(stored.farm().state().name().equals("READY"), "profile failed for " + entry.getKey() + ": " + stored.farm().state());
                check(stored.farm().profile().ratesPer20Ticks().getOrDefault(Material.ROTTEN_FLESH, 0.0) > 0,
                        "no rotten flesh in the profile of " + entry.getKey());
                pass("PROFILE", entry.getKey() + " " + stored.farm().profile().ratesPer20Ticks());
            }
            // Through /bake stasis: pistons and mobs in transition are retried by the plugin itself.
            for (var farm : ids.values()) check(creator.performCommand("bake stasis --id " + farm), "stasis command rejected");
            next(4);
        } else if (phase == 4) {
            if (ids.values().stream().anyMatch(farm -> !state(farm).equals("STASIS"))) {
                check(ticks < 400, "stasis did not complete automatically: " + states());
                return;
            }
            pass("STASIS", "all three farms entered stasis after " + ticks + " server ticks without operator cleanup");
            if (!mode.equals("acceptance")) {
                restoreAll();
                pass("PASSED", "mode=smoke: registered after reset, profiled and stasised all three farms from the safe pad");
                phase = 99;
                getServer().shutdown();
                return;
            }
            next(5);
        } else if (phase == 5) {
            // Start the exact window right after an emulation update so the stored state is current.
            if (ticks < 40) return;
            for (var entry : ids.entrySet()) {
                ResolvedOutputInventory.clear(outputBlock(entry.getKey()));
                windowStart.put(entry.getKey(), stored(entry.getValue()).emulation().orElse(EmulationState.EMPTY));
            }
            phaseGameTime = world.getGameTime();
            // Not blocking: phase 6 must observe the exact end of the same 1,200-tick window.
            benchmark("stasis", false, path -> stasisBenchmark = path);
            next(6);
        } else if (phase == 6) {
            if (world.getGameTime() - phaseGameTime < UPDATES * 20L) return;
            check(world.getGameTime() - phaseGameTime == UPDATES * 20L, "missed the end of the 1,200-tick window");
            verifyExactProduction();
            next(7);
        } else if (phase == 7) {
            if (stasisBenchmark == null) { check(ticks < 400, "stasis benchmark not recorded"); return; }
            compareBenchmarks();
            restoreAll();
            var path = getDataFolder().toPath().resolve("acceptance-result.yml");
            result.set("original-benchmark", originalBenchmark.getFileName().toString());
            result.set("stasis-benchmark", stasisBenchmark.getFileName().toString());
            result.set("forbidden-operator-commands", forbidden);
            result.save(path.toFile());
            pass("PASSED", "mode=acceptance: exact 1,200-tick production, TPS/MSPT recovery, restore; result " + path);
            phase = 99;
            getServer().shutdown();
        }
    }

    private void verifyExactProduction() {
        for (var entry : ids.entrySet()) {
            var stored = stored(entry.getValue());
            var start = windowStart.get(entry.getKey());
            var end = stored.emulation().orElseThrow();
            var counts = ResolvedOutputInventory.count(outputBlock(entry.getKey()));
            var farmResult = new LinkedHashMap<String, Object>();
            for (var rate : stored.farm().profile().outputs().getFirst().entrySet()) {
                var type = rate.getKey();
                var before = start.outputs().isEmpty() ? BigDecimal.ZERO
                        : start.outputs().getFirst().fractions().getOrDefault(type, BigDecimal.ZERO);
                int buffered = start.outputs().isEmpty() ? 0 : start.outputs().getFirst().buffer().getOrDefault(type, 0);
                // Sixty updates add exactly 60 x rate; the whole part is produced, the rest carries.
                var total = before.add(BigDecimal.valueOf(rate.getValue()).multiply(BigDecimal.valueOf(UPDATES)));
                int expected = total.setScale(0, RoundingMode.FLOOR).intValueExact() + buffered;
                int stillBuffered = end.outputs().getFirst().buffer().getOrDefault(type, 0);
                int actual = counts.getOrDefault(type, 0) + stillBuffered;
                var fraction = end.outputs().getFirst().fractions().getOrDefault(type, BigDecimal.ZERO);
                check(actual == expected, "production mismatch " + entry.getKey() + " " + type + ": expected " + expected
                        + " in 1,200 game ticks, resolved double chest + buffer hold " + actual);
                check(fraction.compareTo(total.subtract(total.setScale(0, RoundingMode.FLOOR))) == 0,
                        "fraction mismatch " + entry.getKey() + " " + type);
                farmResult.put(type.getKey().toString(), Map.of("rate-per-20-ticks", rate.getValue(), "expected", expected,
                        "actual", actual, "carried-fraction", fraction.toPlainString()));
                pass("EXACT_1200", entry.getKey() + " " + type + " expected=" + expected + " actual=" + actual + " fraction=" + fraction);
            }
            result.set("exact-1200-tick-production." + entry.getKey(), farmResult);
        }
    }

    private void compareBenchmarks() {
        var original = YamlConfiguration.loadConfiguration(originalBenchmark.toFile());
        var stasis = YamlConfiguration.loadConfiguration(stasisBenchmark.toFile());
        check(original.getLong("server-ticks-without-game-tick") == 0 && stasis.getLong("server-ticks-without-game-tick") == 0,
                "the game was frozen during a benchmark");
        check(original.getConfigurationSection("players").getValues(false).equals(stasis.getConfigurationSection("players").getValues(false)),
                "the player position differed between the original and the stasis benchmark");
        double originalMedian = original.getDouble("mspt.median");
        double stasisMedian = stasis.getDouble("mspt.median");
        double stasisTps = stasis.getDouble("tps");
        result.set("original", Map.of("tps", original.getDouble("tps"), "mspt-median", originalMedian, "mspt-p95", original.getDouble("mspt.p95")));
        result.set("stasis", Map.of("tps", stasisTps, "mspt-median", stasisMedian, "mspt-p95", stasis.getDouble("mspt.p95")));
        check(stasisTps >= 19.5, "stasis phase does not hold 20 TPS: " + stasisTps);
        check(stasisMedian < originalMedian, "stasis did not reduce median MSPT: " + originalMedian + " -> " + stasisMedian);
        pass("TPS", "original tps=" + original.getDouble("tps") + " mspt median=" + originalMedian + " p95=" + original.getDouble("mspt.p95")
                + "; stasis tps=" + stasisTps + " mspt median=" + stasisMedian + " p95=" + stasis.getDouble("mspt.p95"));
    }

    private void restoreAll() {
        for (var entry : ids.entrySet()) {
            check(creator.performCommand("bake unstasis --id " + entry.getValue()), "unstasis command rejected");
            check(state(entry.getValue()).equals("NEEDS_PROFILING"), "restore failed for " + entry.getKey());
            var farm = lab.mobPerformance().geometry(entry.getKey());
            check(world.getBlockAt(farm.min()[0], farm.min()[1], farm.min()[2]).getType() == Material.GOLD_BLOCK,
                    "original mechanism not restored for " + entry.getKey());
        }
        pass("RESTORED", "all three farms are back as NEEDS_PROFILING with their original blocks");
    }

    private void benchmark(String label, boolean block, java.util.function.Consumer<Path> done) throws Exception {
        waiting = block;
        MobBenchmark.run(this, world, lab.mobPerformance().benchmarkFarms(), lab.mobPerformance().afk(), label, UPDATES * 20,
                path -> {
                    waiting = false;
                    done.accept(path);
                    pass("BENCHMARK", label + " " + path.getFileName());
                }, error -> {
                    getLogger().log(Level.SEVERE, "MOB_REVIEW_FAILED benchmark " + label, error);
                    phase = 99;
                    getServer().shutdown();
                });
    }

    private void standsOnSafePad() {
        var afk = lab.mobPerformance().afk();
        var at = creator.getLocation();
        check(at.getWorld().equals(world) && at.getBlockX() == afk[0] && at.getBlockY() == afk[1] && at.getBlockZ() == afk[2],
                "the client is not on the safe AFK pad: " + at);
        for (var farm : lab.mobPerformance().benchmarkFarmGeometry()) {
            var area = new Cuboid(new BlockPosition(world.getUID(), farm.min()[0], farm.min()[1], farm.min()[2]),
                    new BlockPosition(world.getUID(), farm.max()[0], farm.max()[1], farm.max()[2])).expanded(ProfilingIsolation.MARGIN);
            var belt = new BoundingBox(area.min().x(), area.min().y(), area.min().z(), area.max().x() + 1, area.max().y() + 1, area.max().z() + 1);
            check(!creator.getBoundingBox().overlaps(belt), "the client stands inside the protection belt of " + farm.id());
        }
    }

    private void register(MobPerformanceWorld.Geometry farm) {
        creator.getInventory().clear();
        check(creator.performCommand("bake wand"), "wand command rejected");
        creator.getInventory().setHeldItemSlot(0);
        interact(farm.min(), Action.LEFT_CLICK_BLOCK);
        interact(farm.max(), Action.LEFT_CLICK_BLOCK);
        interact(farm.output(), Action.LEFT_CLICK_BLOCK);
        check(creator.performCommand("bake register"), "register command rejected");
        var saved = storage.loadAll().stream().filter(stored -> stored.farm().mechanism().min().worldId().equals(world.getUID())
                && stored.farm().mechanism().min().x() == farm.min()[0] && stored.farm().mechanism().min().z() == farm.min()[2])
                .findFirst().orElseThrow(() -> new AssertionError("registration of " + farm.id() + " was refused"));
        ids.put(farm.id(), saved.farm().id());
    }

    private void interact(int[] p, Action action) {
        getServer().getPluginManager().callEvent(new PlayerInteractEvent(creator, action, creator.getInventory().getItemInMainHand(),
                world.getBlockAt(p[0], p[1], p[2]), BlockFace.UP, EquipmentSlot.HAND));
    }

    private org.bukkit.block.Block outputBlock(String farm) {
        var output = lab.mobPerformance().geometry(farm).output();
        return world.getBlockAt(output[0], output[1], output[2]);
    }

    private FarmStorage.StoredFarm stored(UUID id) {
        return storage.loadAll().stream().filter(stored -> stored.farm().id().equals(id)).findFirst().orElseThrow();
    }

    private String state(UUID id) { return stored(id).farm().state().name(); }

    private Map<String, String> states() {
        var result = new LinkedHashMap<String, String>();
        ids.forEach((name, id) -> result.put(name, state(id)));
        return result;
    }

    private void next(int phase) { this.phase = phase; ticks = 0; }
    private void pass(String label, String details) { getLogger().info("MOB_REVIEW_" + label + " tick=" + getServer().getCurrentTick() + " " + details); }
    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
