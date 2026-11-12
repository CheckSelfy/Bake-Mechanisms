package io.github.checkselfy.bakemechanisms.lab;

import io.github.checkselfy.bakemechanisms.storage.FarmStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * One fixed-length measurement of the three-farm mob benchmark, recorded to
 * {@code plugins/BakeFarmLab/benchmarks/<millis>-<label>.yml}.
 *
 * <p>The window is counted in game ticks of the benchmark world ({@link World#getGameTime()}), not scheduler ticks:
 * under {@code /tick freeze} Paper keeps running plugin tasks while the worlds stand still. Scheduler ticks are
 * recorded beside them, so a freeze during a run is visible. Every server tick of the window contributes one MSPT
 * sample. Output is counted through the resolved double-chest inventory, and the bundled Spark profiler is started
 * and stopped with {@code --save-to-file} (never uploaded) when the server has it.
 */
public final class MobBenchmark {
    private final JavaPlugin plugin;
    private final World world;
    private final List<MobPerformanceWorld.BenchmarkFarm> farms;
    private final int[] afk;
    private final String label;
    private final int gameTicks;
    private final Consumer<Path> recorded;
    private final Consumer<Exception> failed;
    private final List<Double> mspt = new ArrayList<>();
    private Map<String, Map<String, Integer>> before;
    private Set<String> sparkBefore;
    private boolean spark;
    private long startGameTime;
    private int startServerTick;
    private long startNanos;
    private BukkitTask task;
    private YamlConfiguration record;
    private Map<String, Long> sparkSizes = Map.of();
    private int sparkWait;
    /** Spark writes the profile file asynchronously after {@code profiler stop}; wait this long for it. */
    public static final int SPARK_WAIT_TICKS = 1200;

    private MobBenchmark(JavaPlugin plugin, World world, List<MobPerformanceWorld.BenchmarkFarm> farms, int[] afk,
                         String label, int gameTicks, Consumer<Path> recorded, Consumer<Exception> failed) {
        this.plugin = plugin;
        this.world = Objects.requireNonNull(world, "benchmark world not loaded");
        this.farms = farms;
        this.afk = afk;
        this.label = label;
        this.gameTicks = gameTicks;
        this.recorded = recorded;
        this.failed = failed;
    }

    public static void run(JavaPlugin plugin, World world, List<MobPerformanceWorld.BenchmarkFarm> farms, int[] afk,
                    String label, int gameTicks, Consumer<Path> recorded, Consumer<Exception> failed) throws IOException {
        if (!label.matches("[a-zA-Z0-9_-]{1,48}")) throw new IllegalArgumentException("Use a short letters/numbers/dashes label");
        if (gameTicks < 20 || gameTicks > 72000) throw new IllegalArgumentException("Use 20..72000 game ticks");
        new MobBenchmark(plugin, world, farms, afk, label, gameTicks, recorded, failed).start();
    }

    private void start() throws IOException {
        before = inventories();
        sparkBefore = sparkArtifacts();
        spark = plugin.getServer().getCommandMap().getCommand("spark") != null;
        if (spark) plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), "spark profiler start");
        startGameTime = world.getGameTime();
        startServerTick = plugin.getServer().getCurrentTick();
        startNanos = System.nanoTime();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private void tick() {
        try {
            // The previous server tick's duration; Paper keeps the last 100 in a ring indexed by tick number.
            var times = plugin.getServer().getTickTimes();
            mspt.add(times[Math.floorMod(plugin.getServer().getCurrentTick() - 1, times.length)] / 1_000_000.0);
            if (world.getGameTime() - startGameTime < gameTicks) return;
            task.cancel();
            finish();
            if (!spark) {
                save();
                return;
            }
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::awaitSpark, 1L, 1L);
        } catch (Exception error) {
            task.cancel();
            failed.accept(error);
        }
    }

    /** Everything measured at the end of the window; the record is saved once Spark has written its profile. */
    private void finish() throws IOException {
        long elapsedGame = world.getGameTime() - startGameTime;
        int elapsedServer = plugin.getServer().getCurrentTick() - startServerTick;
        double seconds = (System.nanoTime() - startNanos) / 1e9;
        if (spark) {
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),
                    "spark profiler stop --save-to-file --comment bake-benchmark-" + label);
        }
        record = new YamlConfiguration();
        record.set("label", label);
        record.set("world", world.getUID().toString());
        record.set("start-game-time", startGameTime);
        record.set("end-game-time", startGameTime + elapsedGame);
        record.set("elapsed-game-ticks", elapsedGame);
        record.set("requested-game-ticks", gameTicks);
        record.set("elapsed-server-ticks", elapsedServer);
        // Non-zero only if the game was frozen (/tick freeze) during the window.
        record.set("server-ticks-without-game-tick", elapsedServer - elapsedGame);
        record.set("wall-seconds", round(seconds));
        record.set("tps", round(elapsedGame / seconds));
        record.set("mspt", msptSummary());
        record.set("safe-afk-profile-position", List.of(afk[0], afk[1], afk[2]));
        var players = new LinkedHashMap<String, Object>();
        for (Player player : world.getPlayers()) {
            var location = player.getLocation();
            players.put(player.getName(), List.of(round(location.getX()), round(location.getY()), round(location.getZ())));
        }
        record.set("players", players);
        record.set("farm-states", farmStates());
        record.set("initial-inventory", before);
        var after = inventories();
        record.set("final-inventory", after);
        var delta = new LinkedHashMap<String, Map<String, Integer>>();
        after.forEach((farm, items) -> {
            var change = new LinkedHashMap<String, Integer>();
            var start = before.getOrDefault(farm, Map.of());
            var types = new TreeSet<>(items.keySet());
            types.addAll(start.keySet());
            for (var type : types) change.put(type, items.getOrDefault(type, 0) - start.getOrDefault(type, 0));
            delta.put(farm, change);
        });
        record.set("inventory-delta", delta);
        record.set("profile-rates-per-20-ticks", rates());
        record.set("spark-profiler", spark ? "started and stopped with --save-to-file" : "not available on this server");
    }

    /**
     * Waits until the profile saved by {@code profiler stop} exists and its size stopped changing. Listing the
     * folder right after the stop missed the file, which Spark writes on its own thread a few seconds later.
     */
    private void awaitSpark() {
        try {
            var sizes = new LinkedHashMap<String, Long>();
            var root = plugin.getServer().getPluginsFolder().toPath().resolve("spark");
            for (var name : sparkArtifacts()) if (!sparkBefore.contains(name)) sizes.put(name, Files.size(root.resolve(name)));
            boolean written = !sizes.isEmpty() && sizes.equals(sparkSizes) && sizes.values().stream().allMatch(size -> size > 0);
            sparkSizes = sizes;
            if (!written && ++sparkWait < SPARK_WAIT_TICKS) return;
            task.cancel();
            if (!written) record.set("spark-artifact-timeout-server-ticks", SPARK_WAIT_TICKS);
            save();
        } catch (Exception error) {
            task.cancel();
            failed.accept(error);
        }
    }

    private void save() throws IOException {
        var created = new TreeSet<>(sparkArtifacts());
        created.removeAll(sparkBefore);
        record.set("spark-artifacts-created", List.copyOf(created));
        record.set("spark-artifacts", List.copyOf(sparkArtifacts()));
        var path = plugin.getDataFolder().toPath().resolve("benchmarks/" + System.currentTimeMillis() + "-" + label + ".yml");
        Files.createDirectories(path.getParent());
        record.save(path.toFile());
        recorded.accept(path);
    }

    private Map<String, Object> msptSummary() {
        var sorted = mspt.stream().sorted().toList();
        var summary = new LinkedHashMap<String, Object>();
        summary.put("samples", sorted.size());
        if (sorted.isEmpty()) return summary;
        summary.put("mean", round(sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
        summary.put("median", round(sorted.get(sorted.size() / 2)));
        summary.put("p95", round(sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * .95) - 1))));
        summary.put("max", round(sorted.getLast()));
        return summary;
    }

    private Map<String, Map<String, Integer>> inventories() {
        var result = new LinkedHashMap<String, Map<String, Integer>>();
        for (var farm : farms) {
            var counts = new LinkedHashMap<String, Integer>();
            var output = world.getBlockAt(farm.output()[0], farm.output()[1], farm.output()[2]);
            ResolvedOutputInventory.count(output).forEach((material, count) -> counts.put(material.getKey().toString(), count));
            result.put(farm.id(), counts);
        }
        return result;
    }

    private FarmStorage storage() {
        return new FarmStorage(Objects.requireNonNull(plugin.getServer().getPluginManager().getPlugin("BakeMechanisms"))
                .getDataFolder().toPath().resolve("farms"));
    }

    private Map<String, Object> farmStates() {
        var result = new LinkedHashMap<String, Object>();
        for (var stored : storage().loadAll()) if (stored.farm().mechanism().min().worldId().equals(world.getUID())) {
            result.put(stored.farm().title(), stored.farm().state().name());
        }
        return result;
    }

    private Map<String, Object> rates() {
        var result = new LinkedHashMap<String, Object>();
        for (var stored : storage().loadAll()) if (stored.farm().mechanism().min().worldId().equals(world.getUID())) {
            if (stored.farm().profile() == null) {
                result.put(stored.farm().title(), Map.of());
                continue;
            }
            var outputs = new ArrayList<Map<String, Double>>();
            for (var output : stored.farm().profile().outputs()) {
                var formatted = new LinkedHashMap<String, Double>();
                output.forEach((material, rate) -> formatted.put(material.getKey().toString(), rate));
                outputs.add(formatted);
            }
            result.put(stored.farm().title(), outputs);
        }
        return result;
    }

    private Set<String> sparkArtifacts() throws IOException {
        var spark = plugin.getServer().getPluginsFolder().toPath().resolve("spark");
        var result = new TreeSet<String>();
        if (!Files.isDirectory(spark)) return result;
        try (var paths = Files.walk(spark, 3)) {
            paths.filter(Files::isRegularFile).map(path -> spark.relativize(path).toString().replace('\\', '/'))
                    .filter(path -> !path.startsWith("tmp/"))
                    // Saved profiles and heap dumps only; Spark also keeps config.json and an activity.json log there.
                    .filter(path -> path.endsWith(".sparkprofile") || path.endsWith(".sparkheap") || path.endsWith(".hprof"))
                    .forEach(result::add);
        }
        return result;
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }
}
