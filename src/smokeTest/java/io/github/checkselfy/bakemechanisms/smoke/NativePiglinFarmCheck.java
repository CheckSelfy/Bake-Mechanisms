package io.github.checkselfy.bakemechanisms.smoke;

import io.github.checkselfy.bakemechanisms.marking.BlockPosition;
import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry;
import io.github.checkselfy.bakemechanisms.marking.PaperOutputResolver;
import io.github.checkselfy.bakemechanisms.profiling.PaperProfilingWorld;
import io.github.checkselfy.bakemechanisms.profiling.ProductionProfile;
import io.github.checkselfy.bakemechanisms.stasis.FileSnapshotStore;
import io.github.checkselfy.bakemechanisms.stasis.PaperStasisWorld;
import io.github.checkselfy.bakemechanisms.stasis.SnapshotPayload;
import io.github.checkselfy.bakemechanisms.stasis.StasisService;
import io.github.checkselfy.bakemechanisms.stasis.StasisStabilizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.PigZombie;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;

/**
 * A gold-farm shaped mechanism on real ticks: ordinary despawnable zombified piglins, chicken jockeys and native
 * kills that keep happening while stasis waits for a stable tick. No persistence edits, no /kill, no tick freeze
 * and no forced dismount: the same path as the {@code /bake stasis} command must stabilize on its own.
 */
final class NativePiglinFarmCheck extends BukkitRunnable {
    private static final int X = 1024;
    private static final int MAX_TICKS = 400;
    private final JavaPlugin plugin;
    private final World world;
    private final Consumer<Throwable> finish;
    private final Random random = new Random(12);
    private final UUID creator = UUID.randomUUID();
    private final Cuboid region;
    private final BoundingBox bounds;
    private final FileSnapshotStore store;
    private final PaperOutputResolver resolver = new PaperOutputResolver();
    private final PaperStasisWorld adapter;
    private final List<String> progress = new ArrayList<>();
    private final Map<UUID, Boolean> despawnable = new HashMap<>();
    private final Map<UUID, Boolean> babies = new HashMap<>();
    private StasisService service;
    private UUID farm;
    private Chicken stableJockey, dyingRiderJockey, dyingVehicleJockey, mountedNow, dismountedNow;
    private PigZombie stableRider, dyingRider, survivingRider, mountingRider, dismountingRider;
    private boolean deathKeptTreeMounted;
    private int tick, enteredAt = -1, restoredAt = -1;
    private Set<UUID> members = Set.of();
    private final Set<UUID> livingMembers = new HashSet<>();

    static void run(JavaPlugin plugin, Consumer<Throwable> finish) {
        var check = new NativePiglinFarmCheck(plugin, finish);
        try { check.build(); check.runTaskTimer(plugin, 1, 1); }
        catch (Throwable error) { check.cleanup(); finish.accept(error); }
    }

    private NativePiglinFarmCheck(JavaPlugin plugin, Consumer<Throwable> finish) {
        this.plugin = plugin;
        this.world = plugin.getServer().getWorlds().getFirst();
        this.finish = finish;
        this.region = new Cuboid(point(X, 300, 0), point(X + 15, 305, 15));
        this.bounds = PaperProfilingWorld.box(region);
        this.store = new FileSnapshotStore(plugin.getDataFolder().toPath().resolve("piglin-" + UUID.randomUUID()), "26.3");
        this.adapter = new PaperStasisWorld(plugin, resolver);
    }

    private void build() {
        for (int cx = (X >> 4) - 1; cx <= ((X + 16) >> 4) + 1; cx++) for (int cz = -1; cz <= 1; cz++) {
            world.getChunkAt(cx, cz).addPluginChunkTicket(plugin);
        }
        clear();
        for (int x = X; x <= X + 15; x++) for (int y = 300; y <= 305; y++) for (int z = 0; z <= 15; z++) {
            boolean wall = x == X || x == X + 15 || z == 0 || z == 15;
            world.getBlockAt(x, y, z).setType(y == 300 ? Material.STONE
                    : wall && y <= 303 ? Material.GLASS : Material.AIR, false);
        }
        var spawnerBlock = world.getBlockAt(X + 7, 301, 7);
        spawnerBlock.setType(Material.SPAWNER, false);
        var spawner = (CreatureSpawner) spawnerBlock.getState(false);
        spawner.setSpawnedType(EntityType.ZOMBIFIED_PIGLIN);
        spawner.update(true, false);
        // Ordinary spawner mobs: native spawn initialisation, no persistence or name tag.
        for (int i = 0; i < 12; i++) {
            world.spawn(new Location(world, X + 2.5 + i % 6 * 2, 301, 3.5 + i / 6 * 8), PigZombie.class, SpawnReason.SPAWNER);
        }
        stableJockey = chicken(3.5, 11.5);
        stableRider = baby(3.5, 11.5);
        stableJockey.addPassenger(stableRider);
        dyingRiderJockey = chicken(6.5, 11.5);
        dyingRider = baby(6.5, 11.5);
        dyingRiderJockey.addPassenger(dyingRider);
        dyingVehicleJockey = chicken(9.5, 11.5);
        survivingRider = baby(9.5, 11.5);
        dyingVehicleJockey.addPassenger(survivingRider);
        mountedNow = chicken(12.5, 11.5);
        mountingRider = baby(12.5, 9.5);
        dismountedNow = chicken(12.5, 5.5);
        dismountingRider = baby(12.5, 5.5);
        dismountedNow.addPassenger(dismountingRider);
        var output = world.getBlockAt(X + 16, 300, 0);
        output.setType(Material.BARREL, false);
        var registry = new FarmRegistry(FarmRegistry.DEFAULT_MAX_VOLUME);
        farm = registry.register(creator, region, resolver.resolve(output)).id();
        registry.markReady(farm, new ProductionProfile(Map.of(Material.ROTTEN_FLESH, 2.5), 3600));
        service = new StasisService(registry, adapter, store);
    }

    private Chicken chicken(double x, double z) {
        var chicken = world.spawn(new Location(world, X + x, 301, z), Chicken.class, SpawnReason.JOCKEY);
        chicken.setIsChickenJockey(true);
        return chicken;
    }

    private PigZombie baby(double x, double z) {
        return world.spawn(new Location(world, X + x, 301, z), PigZombie.class, SpawnReason.SPAWNER, piglin -> piglin.setBaby());
    }

    @Override public void run() {
        try {
            tick++;
            if (tick > MAX_TICKS) throw new AssertionError("native piglin scenario timed out at tick " + tick
                    + ", progress=" + progress);
            if (enteredAt < 0 && tick == 10) startStasis();
            else if (enteredAt < 0 && tick > 10) killOne(); // The farm keeps killing while stasis waits.
            else if (enteredAt > 0 && restoredAt < 0 && tick == enteredAt + 5) {
                requireEmpty("an original resumed inside the filler");
                restoreAfterRestart();
            } else if (restoredAt > 0 && tick == restoredAt + 40) {
                assertRestoredTreesAreStable();
                cleanup();
                cancel();
                plugin.getLogger().info("NATIVE_PIGLIN_STASIS_SMOKE_PASSED: " + members.size()
                        + " members (" + livingMembers.size() + " mobs, the rest native loot), waited " + (enteredAt - 10) + " ticks, progress=" + progress.size()
                        + ", deathKeptTreeMounted=" + deathKeptTreeMounted);
                finish.accept(null);
            }
        } catch (Throwable error) {
            cancel();
            cleanup();
            finish.accept(error);
        }
    }

    private void startStasis() {
        for (var entity : world.getNearbyEntities(bounds)) if (entity instanceof PigZombie piglin) {
            despawnable.put(piglin.getUniqueId(), piglin.getRemoveWhenFarAway());
            babies.put(piglin.getUniqueId(), !piglin.isAdult());
            check(piglin.getRemoveWhenFarAway(), "fixture piglin is persistent; the scenario needs ordinary spawner mobs");
        }
        // Native kills in the same tick: a jockey rider, a jockey mount and standalone mobs.
        dyingRider.damage(1000);
        dyingVehicleJockey.damage(1000);
        killOne();
        killOne();
        check(dyingRider.isDead() && dyingVehicleJockey.isDead(), "native kill did not start a death");
        deathKeptTreeMounted = dyingRider.getVehicle() != null || !dyingVehicleJockey.getPassengers().isEmpty();
        // Mount and dismount in the very tick of the first attempt.
        mountedNow.addPassenger(mountingRider);
        dismountingRider.leaveVehicle();
        var stabilizer = new StasisStabilizer(service, task -> plugin.getServer().getScheduler().runTaskLater(plugin, task, 1L));
        check(stabilizer.enter(farm, creator, new StasisStabilizer.Listener() {
            @Override public void waiting(int waitedTicks, String reason) {
                progress.add(waitedTicks + ": " + reason);
                plugin.getLogger().info("NATIVE_PIGLIN_STABILIZING " + waitedTicks + ": " + reason);
            }
            @Override public void entered() {
                enteredAt = tick;
                try { afterEnter(); } catch (Throwable error) { cancel(); cleanup(); finish.accept(error); }
            }
            @Override public void failed(IllegalArgumentException error) {
                cancel(); cleanup(); finish.accept(new AssertionError("stasis did not stabilize: " + progress, error));
            }
        }), "stabilizer refused the first request");
    }

    private void afterEnter() throws Exception {
        if (deathKeptTreeMounted) check(!progress.isEmpty(), "a dying jockey was captured without waiting");
        var snapshots = store.loadActive();
        check(snapshots.size() == 1, "expected one active snapshot");
        var groups = SnapshotPayload.decode(snapshots.getFirst().data()).groups();
        members = new HashSet<>();
        for (var group : groups) members.addAll(group.members());
        check(!members.contains(dyingRider.getUniqueId()) && !members.contains(dyingVehicleJockey.getUniqueId()),
                "a corpse was saved in the snapshot");
        requireGroup(groups, stableJockey, stableRider);
        requireGroup(groups, mountedNow, mountingRider);
        check(groups.stream().anyMatch(group -> group.members().equals(List.of(dismountingRider.getUniqueId()))),
                "dismounted rider was not saved on its own");
        check(groups.stream().anyMatch(group -> group.members().equals(List.of(dismountedNow.getUniqueId()))),
                "chicken left by its rider was not saved on its own");
        check(members.contains(survivingRider.getUniqueId()), "rider of a killed chicken was lost");
        check(despawnable.keySet().stream().filter(id -> !id.equals(dyingRider.getUniqueId())).anyMatch(members::contains),
                "no ordinary spawner piglin was captured");
        // Same tick as capture: nothing original, alive or dying, remains under the filler.
        requireEmpty("an original survived the handoff");
        for (var id : members) check(plugin.getServer().getEntity(id) == null, "saved original still exists: " + id);
        for (int x = X; x <= X + 15; x++) for (int y = 300; y <= 305; y++) for (int z = 0; z <= 15; z++) {
            check(FillerAssertions.matches(world.getBlockAt(x, y, z), region), "filler incomplete at " + x + "," + y + "," + z);
        }
    }

    private void restoreAfterRestart() throws Exception {
        // A fresh registry and service read only the files on disk, as after a server restart.
        var restarted = new StasisService(new FarmRegistry(FarmRegistry.DEFAULT_MAX_VOLUME), adapter, store);
        check(restarted.recover().isEmpty(), "recovery orphaned the farm");
        restarted.restore(farm, creator);
        restoredAt = tick;
        for (var id : members) {
            var entity = plugin.getServer().getEntity(id);
            check(entity != null && entity.isValid(), "restored member missing: " + id);
            if (entity instanceof LivingEntity) livingMembers.add(id);
            if (entity instanceof PigZombie piglin && despawnable.containsKey(id)) {
                check(piglin.getRemoveWhenFarAway() == despawnable.get(id), "despawn policy changed for " + id);
                check(!piglin.isAdult() == babies.get(id), "age changed for " + id);
            }
        }
        requireRiding(stableJockey, stableRider);
        requireRiding(mountedNow, mountingRider);
        check(((Chicken) plugin.getServer().getEntity(stableJockey.getUniqueId())).isChickenJockey(), "jockey flag lost");
        check(plugin.getServer().getEntity(dismountingRider.getUniqueId()).getVehicle() == null, "dismounted rider remounted");
        check(world.getBlockAt(X + 7, 301, 7).getState() instanceof CreatureSpawner spawner
                && spawner.getSpawnedType() == EntityType.ZOMBIFIED_PIGLIN, "spawner lost");
        check(plugin.getServer().getEntity(dyingRider.getUniqueId()) == null, "a killed rider came back");
    }

    private void assertRestoredTreesAreStable() {
        requireRiding(stableJockey, stableRider);
        requireRiding(mountedNow, mountingRider);
        for (var pair : List.of(List.of(stableJockey, stableRider), List.of(mountedNow, mountingRider))) {
            var vehicle = plugin.getServer().getEntity(pair.get(0).getUniqueId());
            var rider = plugin.getServer().getEntity(pair.get(1).getUniqueId());
            check(vehicle.getLocation().distanceSquared(rider.getLocation()) < 4, "rider was not positioned on its vehicle");
        }
        for (var id : members) {
            var entity = plugin.getServer().getEntity(id);
            // Restored loot is ordinary world state again: a piglin may natively pick it up.
            if (!livingMembers.contains(id)) continue;
            check(entity != null && entity.isValid(), "restored mob did not survive 40 ticks: " + id
                    + (entity == null ? "" : " " + entity.getType() + " health=" + ((LivingEntity) entity).getHealth()));
        }
    }

    private void requireGroup(List<SnapshotPayload.EntityGroup> groups, Entity vehicle, Entity rider) {
        check(groups.stream().anyMatch(group -> group.rootId().equals(vehicle.getUniqueId())
                && group.members().equals(List.of(vehicle.getUniqueId(), rider.getUniqueId()))),
                "jockey tree not saved as one group: " + vehicle.getUniqueId());
    }

    private void requireRiding(Entity vehicle, Entity rider) {
        var restoredVehicle = plugin.getServer().getEntity(vehicle.getUniqueId());
        var restoredRider = plugin.getServer().getEntity(rider.getUniqueId());
        check(restoredVehicle != null && restoredRider != null
                && restoredVehicle.getPassengers().contains(restoredRider)
                && restoredVehicle.equals(restoredRider.getVehicle()), "jockey not restored as one tree: " + vehicle.getUniqueId());
    }

    /** One more native kill of a standalone mob, as the golem of a gold farm would do. */
    private void killOne() {
        var candidates = world.getNearbyEntities(bounds).stream()
                .filter(entity -> entity instanceof PigZombie piglin && piglin.isValid() && !piglin.isInsideVehicle()
                        && piglin.getPassengers().isEmpty() && piglin.isAdult())
                .map(LivingEntity.class::cast).toList();
        if (candidates.size() > 2) candidates.get(random.nextInt(candidates.size())).damage(1000);
    }

    private void requireEmpty(String message) {
        var left = world.getNearbyEntities(bounds).stream().filter(entity -> entity.getBoundingBox().overlaps(bounds)).toList();
        check(left.isEmpty(), message + ": " + left.stream().map(entity -> entity.getType() + "/" + entity.isDead()).toList());
    }

    private void clear() {
        world.getNearbyEntities(new BoundingBox(X - 2, 298, -2, X + 18, 310, 18)).forEach(Entity::remove);
    }

    private void cleanup() {
        clear();
        world.removePluginChunkTickets(plugin);
    }

    private BlockPosition point(int x, int y, int z) { return new BlockPosition(world.getUID(), x, y, z); }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
