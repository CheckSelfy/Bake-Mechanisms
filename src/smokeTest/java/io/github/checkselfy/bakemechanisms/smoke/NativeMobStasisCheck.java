package io.github.checkselfy.bakemechanisms.smoke;

import io.github.checkselfy.bakemechanisms.marking.BlockPosition;
import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry;
import io.github.checkselfy.bakemechanisms.marking.PaperOutputResolver;
import io.github.checkselfy.bakemechanisms.profiling.ProductionProfile;
import io.github.checkselfy.bakemechanisms.stasis.FileSnapshotStore;
import io.github.checkselfy.bakemechanisms.stasis.PaperStasisWorld;
import io.github.checkselfy.bakemechanisms.stasis.SnapshotPayload;
import io.github.checkselfy.bakemechanisms.stasis.StasisStabilizer;
import io.github.checkselfy.bakemechanisms.stasis.StasisService;
import io.github.checkselfy.bakemechanisms.stasis.TransientStasisException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Entity;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.PigZombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

/**
 * Stream B of #12 on real Paper: a live zombified-piglin cell — non-persistent spawner mobs, chicken jockeys,
 * plain chickens and an iron golem making native kills — enters stasis and is restored with no operator cleanup:
 * no {@code /tick freeze}, {@code /kill}, persistence edits or dismounting. The test only spawns the cell and lets
 * it run; the one rider it kills is killed natively by the golem's attack, right before entry, so that the first
 * attempt deterministically meets a dying passenger and must be retried automatically.
 *
 * <p>{@link Mode#IN_PROCESS} restores in the same JVM. {@link Mode#CAPTURE} and {@link Mode#RESTORE} run in two
 * JVMs with a clean shutdown between them, so the restore can only use the durable snapshot.
 */
final class NativeMobStasisCheck implements Listener {
    enum Mode { IN_PROCESS, CAPTURE, RESTORE }

    private static final int X = 480, Y = 300, Z = 32, SIZE = 13;
    private static final int WARMUP_TICKS = 200;
    /** Offset of the glass wall between the golem's arena and the shelter. */
    private static final int SHELTER_WALL = 8;
    private final JavaPlugin plugin;
    private final Mode mode;
    private final Consumer<Throwable> finish;
    private final World world;
    private final FarmRegistry registry = new FarmRegistry(FarmRegistry.DEFAULT_MAX_VOLUME);
    private final PaperOutputResolver resolver = new PaperOutputResolver();
    private final FileSnapshotStore store;
    private final java.nio.file.Path baseline;
    private final Set<UUID> golemKills = new HashSet<>();
    private final List<String> reasons = new ArrayList<>();
    private UUID creator = UUID.randomUUID();
    private StasisService service;
    private FarmRegistry.MarkedFarm farm;
    private BukkitTask task;
    private int ticks;
    private int waited;
    private boolean entering;

    private NativeMobStasisCheck(JavaPlugin plugin, Mode mode, Consumer<Throwable> finish) {
        this.plugin = plugin;
        this.mode = mode;
        this.finish = finish;
        this.world = plugin.getServer().getWorlds().getFirst();
        var root = plugin.getDataFolder().toPath().resolve("native-mobs");
        this.store = new FileSnapshotStore(root.resolve(mode == Mode.IN_PROCESS ? "in-process-" + UUID.randomUUID() : "farms"), "26.3");
        this.baseline = root.resolve("baseline.yml");
    }

    static void run(JavaPlugin plugin, Mode mode, Consumer<Throwable> finish) {
        var check = new NativeMobStasisCheck(plugin, mode, finish);
        try {
            check.keepLoaded();
            if (mode == Mode.RESTORE) {
                check.restoreAfterRestart();
                check.done(null);
                return;
            }
            check.build();
            plugin.getServer().getPluginManager().registerEvents(check, plugin);
            check.task = plugin.getServer().getScheduler().runTaskTimer(plugin, check::step, 1L, 1L);
        } catch (Throwable error) {
            check.done(error);
        }
    }

    private void keepLoaded() {
        for (int cx = (X >> 4) - 1; cx <= ((X + SIZE) >> 4) + 1; cx++) {
            for (int cz = (Z >> 4) - 1; cz <= ((Z + SIZE) >> 4) + 1; cz++) world.getChunkAt(cx, cz).addPluginChunkTicket(plugin);
        }
    }

    private void build() throws Exception {
        for (var previous : store.loadActive()) store.deactivate(previous.farm().id());
        for (var entity : world.getNearbyEntities(new BoundingBox(X - 2, Y - 2, Z - 2, X + SIZE + 2, Y + 8, Z + SIZE + 2))) {
            if (!(entity instanceof Player)) entity.remove();
        }
        for (int x = X; x <= X + SIZE; x++) for (int y = Y; y <= Y + 5; y++) for (int z = Z; z < Z + SIZE; z++) {
            var block = world.getBlockAt(x, y, z);
            if (block.getState(false) instanceof Container container) container.getInventory().clear();
            boolean wall = x == X || x == X + SIZE - 1 || z == Z || z == Z + SIZE - 1;
            block.setType(x == X + SIZE ? Material.AIR : y == Y ? Material.STONE
                    : y == Y + 5 || wall ? Material.GLASS : Material.AIR, false);
        }
        // A glass wall splits the cell. In the arena (dz 1..7) the golem kills piglins natively during warmup.
        // The shelter (dz 9..11) is out of its sight and reach, so a jockey and several spawner piglins always
        // survive; otherwise the golem sometimes killed every rider first and the check failed by chance.
        for (int x = X + 1; x < X + SIZE - 1; x++) for (int y = Y + 1; y < Y + 5; y++) {
            world.getBlockAt(x, y, Z + SHELTER_WALL).setType(Material.GLASS, false);
        }
        var output = world.getBlockAt(X + SIZE, Y, Z);
        output.setType(Material.BARREL, false);
        var golem = world.spawn(at(6.5, 6.5), IronGolem.class, SpawnReason.CUSTOM, false, created -> { });
        golem.setPersistent(true);
        // Spawner mobs: non-persistent and despawnable, exactly as Paper spawns them.
        for (int i = 0; i < 10; i++) {
            world.spawn(at(2.5 + i % 5 * 2, 2.5 + i / 5 * 8), PigZombie.class, SpawnReason.SPAWNER, false, piglin -> {
                piglin.setPersistent(false);
                piglin.setRemoveWhenFarAway(true);
                piglin.setAdult();
            });
        }
        for (int i = 0; i < 3; i++) {
            // Jockeys 0 and 2 in the shelter, jockey 1 in the arena with the golem.
            var chicken = world.spawn(at(3.5 + i * 3, i == 1 ? 4.5 : 9.5), Chicken.class, SpawnReason.JOCKEY, false, created -> {
                created.setIsChickenJockey(true);
                created.setPersistent(false);
            });
            var rider = world.spawn(chicken.getLocation(), PigZombie.class, SpawnReason.JOCKEY, false, piglin -> {
                piglin.setBaby();
                piglin.setPersistent(false);
                piglin.setRemoveWhenFarAway(true);
            });
            check(chicken.addPassenger(rider), "could not mount jockey " + i);
        }
        for (int i = 0; i < 2; i++) {
            world.spawn(at(9.5, 3.5 + i * 2), Chicken.class, SpawnReason.SPAWNER, false, chicken -> chicken.setPersistent(false));
        }
        var mechanism = new Cuboid(point(X, Y, Z), point(X + SIZE - 1, Y + 5, Z + SIZE - 1));
        var registered = registry.register(creator, mechanism, resolver.resolve(output));
        registry.markReady(registered.id(), new ProductionProfile(Map.of(Material.ROTTEN_FLESH, 2.5), 3600));
        farm = registry.requireCreator(registered.id(), creator);
        service = new StasisService(registry, new PaperStasisWorld(plugin, resolver), store);
    }

    @EventHandler public void onDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof PigZombie && event.getEntity().getLastDamageCause() instanceof EntityDamageByEntityEvent damage
                && damage.getDamager() instanceof IronGolem && box().contains(event.getEntity().getLocation().toVector())) {
            golemKills.add(event.getEntity().getUniqueId());
        }
    }

    private void step() {
        try {
            if (++ticks < WARMUP_TICKS || entering) return;
            entering = true;
            // A natively dying rider: the golem's attack kills it, its death animation keeps it on the chicken.
            var golem = world.getNearbyEntities(box()).stream().filter(IronGolem.class::isInstance).findFirst().orElseThrow();
            var rider = (PigZombie) world.getNearbyEntities(box()).stream()
                    .filter(entity -> entity instanceof PigZombie piglin && piglin.isValid() && piglin.getVehicle() instanceof Chicken)
                    .findFirst().orElseThrow(() -> new AssertionError("no living jockey left after warmup"));
            rider.damage(1000, golem);
            check(rider.isDead() && rider.getVehicle() != null, "the killed rider is not in its death animation on the chicken");
            new StasisStabilizer(service, next -> plugin.getServer().getScheduler().runTaskLater(plugin, next, 1L))
                    .enter(farm.id(), creator, new StasisStabilizer.Listener() {
                        @Override public void waiting(TransientStasisException reason, int retry, int left) {
                            waited = retry;
                            if (reasons.isEmpty() || !reasons.getLast().equals(reason.getMessage())) reasons.add(reason.getMessage());
                        }

                        @Override public void entered() {
                            try {
                                afterEntry();
                            } catch (Throwable error) {
                                done(error);
                            }
                        }

                        @Override public void failed(IllegalArgumentException error) {
                            done(error);
                        }
                    });
        } catch (Throwable error) {
            done(error);
        }
    }

    private void afterEntry() throws Exception {
        check(waited > 0, "the dying rider did not make the entry wait");
        check(waited <= 40, "stabilization took " + waited + " ticks");
        var snapshot = store.loadActive().stream().filter(saved -> saved.farm().id().equals(farm.id())).findFirst().orElseThrow();
        var groups = SnapshotPayload.decode(snapshot.data()).groups();
        var members = groups.stream().flatMap(group -> group.members().stream()).collect(Collectors.toSet());
        // No original survives anywhere, and nothing living is left inside the filler.
        for (var id : members) check(plugin.getServer().getEntity(id) == null, "original survived stasis: " + id);
        for (var entity : world.getNearbyEntities(box())) {
            check(!entity.isValid() || entity instanceof Player, "living entity left in the filler: " + entity.getType());
        }
        var jockeyGroups = groups.stream().filter(group -> group.members().size() == 2).toList();
        // The golem keeps killing in the arena during warmup and retries, so only the survivors are known there;
        // the shelter guarantees at least one jockey and several spawner piglins.
        check(!jockeyGroups.isEmpty(), "no living chicken jockey was captured as a passenger tree");
        check(members.size() >= 8, "too few entities captured: " + members.size());
        var expected = new YamlConfiguration();
        expected.set("farm", farm.id().toString());
        expected.set("creator", creator.toString());
        expected.set("members", members.stream().map(UUID::toString).sorted().toList());
        for (var group : jockeyGroups) {
            var rider = group.members().stream().filter(id -> !id.equals(group.rootId())).findFirst().orElseThrow();
            expected.set("jockeys." + rider, group.rootId().toString());
        }
        plugin.getLogger().info("NATIVE_MOB stasis entered after " + waited + " automatic retries " + reasons + "; "
                + golemKills.size() + " natural golem kills during warmup; " + members.size() + " entities, "
                + jockeyGroups.size() + " jockeys");
        if (mode == Mode.CAPTURE) {
            Files.createDirectories(baseline.getParent());
            expected.save(baseline.toFile());
            plugin.getLogger().info("NATIVE_MOB_CAPTURE_PASSED: " + members.size() + " entities in a durable snapshot before shutdown");
            done(null);
            return;
        }
        service.restore(farm.id(), creator);
        verifyRestored(expected);
        plugin.getLogger().info("NATIVE_MOB_STASIS_SMOKE_PASSED: live zombified piglins, chickens and " + jockeyGroups.size()
                + " jockeys entered stasis after " + waited + " automatic retries and were restored; "
                + golemKills.size() + " native golem kills during warmup");
        done(null);
    }

    private void restoreAfterRestart() throws Exception {
        var expected = new YamlConfiguration();
        expected.load(baseline.toFile());
        var recovered = new StasisService(registry, new PaperStasisWorld(plugin, resolver), store);
        recovered.recover();
        var farmId = UUID.fromString(expected.getString("farm"));
        creator = UUID.fromString(expected.getString("creator"));
        for (var id : expected.getStringList("members")) {
            check(plugin.getServer().getEntity(UUID.fromString(id)) == null, "an original returned with the restart: " + id);
        }
        recovered.restore(farmId, creator);
        verifyRestored(expected);
        plugin.getLogger().info("NATIVE_MOB_RESTART_PASSED: " + expected.getStringList("members").size()
                + " non-persistent mobs and jockeys restored from the durable snapshot after a clean restart");
    }

    private void verifyRestored(YamlConfiguration expected) {
        var members = expected.getStringList("members").stream().map(UUID::fromString).toList();
        int piglins = 0;
        for (var id : members) {
            var entity = plugin.getServer().getEntity(id);
            check(entity != null && entity.isValid(), "entity not restored: " + id);
            if (entity instanceof PigZombie) {
                piglins++;
                check(!entity.isPersistent(), "a spawner piglin became persistent");
            }
        }
        check(piglins >= 3, "too few zombified piglins restored: " + piglins);
        var jockeys = expected.getConfigurationSection("jockeys");
        check(jockeys != null && !jockeys.getKeys(false).isEmpty(), "jockey baseline missing");
        for (var riderId : jockeys.getKeys(false)) {
            var rider = plugin.getServer().getEntity(UUID.fromString(riderId));
            var chicken = plugin.getServer().getEntity(UUID.fromString(jockeys.getString(riderId)));
            check(rider instanceof PigZombie piglin && !piglin.isAdult(), "jockey rider is not a baby zombified piglin");
            check(chicken instanceof Chicken hen && hen.isChickenJockey(), "jockey chicken lost its jockey flag");
            check(rider.getVehicle() != null && rider.getVehicle().getUniqueId().equals(chicken.getUniqueId()),
                    "rider is not riding its chicken after restore");
        }
        var mobs = members.stream().filter(id -> !(plugin.getServer().getEntity(id) instanceof Item)).collect(Collectors.toSet());
        var live = world.getNearbyEntities(box()).stream().filter(Entity::isValid).filter(entity -> !(entity instanceof Item))
                .map(Entity::getUniqueId).collect(Collectors.toSet());
        check(live.equals(mobs), "live mobs in the farm differ from the captured ones: live " + live.size() + ", captured " + mobs.size());
    }

    private void done(Throwable error) {
        if (task != null) task.cancel();
        HandlerList.unregisterAll(this);
        finish.accept(error);
    }

    private BoundingBox box() {
        return new BoundingBox(X, Y, Z, X + SIZE, Y + 6, Z + SIZE);
    }

    private Location at(double dx, double dz) {
        return new Location(world, X + dx, Y + 1, Z + dz);
    }

    private BlockPosition point(int x, int y, int z) {
        return new BlockPosition(world.getUID(), x, y, z);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
