package io.github.checkselfy.bakemechanisms.smoke;

import io.github.checkselfy.bakemechanisms.marking.BlockPosition;
import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry;
import io.github.checkselfy.bakemechanisms.marking.PaperOutputResolver;
import io.github.checkselfy.bakemechanisms.profiling.ProductionProfile;
import io.github.checkselfy.bakemechanisms.stasis.FileSnapshotStore;
import io.github.checkselfy.bakemechanisms.stasis.PaperStasisWorld;
import io.github.checkselfy.bakemechanisms.stasis.StasisService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/** Real ticks with natural avoidance, target acquisition and panic-driven iron golem spawning. */
final class MobBehaviorCheck extends BukkitRunnable {
    private final JavaPlugin plugin;
    private final World world;
    private final Consumer<Throwable> finish;
    private final List<Mob> actors = new ArrayList<>();
    private final List<Location> starts = new ArrayList<>();
    private StasisService service;
    private UUID farm;
    private final UUID creator = UUID.randomUUID();
    private int scenario = -1, elapsed;
    private boolean after, control;
    private int x;

    static void run(JavaPlugin plugin, Consumer<Throwable> finish) {
        var check = new MobBehaviorCheck(plugin, finish);
        try { check.next(); check.runTaskTimer(plugin, 1, 1); }
        catch (Throwable error) { finish.accept(error); }
    }

    private MobBehaviorCheck(JavaPlugin plugin, Consumer<Throwable> finish) {
        this.plugin = plugin;
        this.world = plugin.getServer().getWorlds().getFirst();
        this.finish = finish;
    }

    private void next() throws Exception {
        scenario++;
        x = 320 + scenario * 64;
        for (int cx = (x >> 4) - 1; cx <= ((x + 31) >> 4) + 1; cx++) for (int cz = -1; cz <= 2; cz++) {
            world.getChunkAt(cx, cz).addPluginChunkTicket(plugin);
        }
        clear();
        for (int bx = x; bx <= x + 31; bx++) for (int y = 300; y <= 305; y++) for (int z = 0; z <= 31; z++) {
            boolean wall = bx == x || bx == x + 31 || z == 0 || z == 31;
            world.getBlockAt(bx, y, z).setType(y == 300 ? Material.STONE : wall && y <= 303 ? Material.GLASS : Material.AIR, false);
        }
        actors.clear(); starts.clear();
        if (scenario == 0) {
            add(EntityType.CREEPER, x + 16.5, 16.5, true);
            add(EntityType.CAT, x + 13.5, 16.5, false);
            onlyAvoidance((Creeper) actors.getFirst());
        } else if (scenario == 1) {
            var slime = (Slime) add(EntityType.SLIME, x + 22.5, 16.5, true);
            slime.setSize(2);
            add(EntityType.IRON_GOLEM, x + 12.5, 16.5, false);
        } else {
            // Three recently rested villagers can summon a golem while panicking at a visible zombie.
            add(EntityType.VILLAGER, x + 18.5, 14.5, true);
            add(EntityType.VILLAGER, x + 18.5, 16.5, true);
            add(EntityType.VILLAGER, x + 18.5, 18.5, true);
            ((Zombie) add(EntityType.ZOMBIE, x + 14.5, 16.5, false)).setShouldBurnInDay(false);
            // One-block-high fences contain villagers while leaving their eye line to the zombie unobstructed.
            for (int z = 13; z <= 19; z++) {
                world.getBlockAt(x + 17, 301, z).setType(Material.OAK_FENCE, false);
                world.getBlockAt(x + 19, 301, z).setType(Material.OAK_FENCE, false);
            }
            for (int z = 13; z <= 19; z += 2) world.getBlockAt(x + 18, 301, z).setType(Material.OAK_FENCE, false);
        }
        var registry = new FarmRegistry(FarmRegistry.DEFAULT_MAX_VOLUME);
        var resolver = new PaperOutputResolver();
        var output = world.getBlockAt(x + 40, 300, 0);
        output.setType(Material.BARREL, false);
        var region = new Cuboid(new BlockPosition(world.getUID(), x, 300, 0), new BlockPosition(world.getUID(), x + 31, 305, 31));
        farm = registry.register(creator, region, resolver.resolve(output)).id();
        registry.markReady(farm, new ProductionProfile(Map.of(Material.IRON_INGOT, 1.0), 3600));
        service = new StasisService(registry, new PaperStasisWorld(plugin, resolver),
                new FileSnapshotStore(plugin.getDataFolder().toPath().resolve("behavior-" + UUID.randomUUID()), "26.3"));
        after = false;
        reset();
        plugin.getLogger().info("MOB_BEHAVIOR_START: " + name());
    }

    private Mob add(EntityType type, double px, double z, boolean ai) {
        var location = new Location(world, px, 301, z);
        var mob = (Mob) world.spawnEntity(location, type);
        mob.setPersistent(true); mob.setRemoveWhenFarAway(false);
        mob.setAI(ai); mob.setAware(ai);
        if (mob instanceof Ageable ageable) ageable.setAdult();
        actors.add(mob); starts.add(location);
        return mob;
    }

    @Override public void run() {
        try {
            elapsed++;
            for (var actor : actors) require(actor.isValid() && !actor.isDead(), "Behavior participant died: " + actor.getType());
            if (control) {
                if (elapsed < 40) return;
                require(actors.getFirst().getLocation().distanceSquared(starts.getFirst()) < 0.3,
                        "Creeper moved without cat in avoidance-only control");
                actors.get(1).teleport(starts.get(1));
                elapsed = 0; control = false;
            }
            if (!ready()) {
                if (elapsed > (scenario == 2 ? 600 : 240)) throw new AssertionError(name()
                        + (after ? " after restore" : " baseline") + " timed out: positions="
                        + actors.stream().map(Entity::getLocation).toList());
                return;
            }
            if (!after) {
                reset();
                if (control) { actors.get(1).teleport(starts.get(1)); control = false; }
                var ids = actors.stream().map(Entity::getUniqueId).toList();
                service.enter(farm, creator);
                service.restore(farm, creator);
                actors.clear();
                for (var id : ids) {
                    var restored = plugin.getServer().getEntity(id);
                    require(restored instanceof Mob && restored.isValid(), "Behavior participant UUID missing: " + id);
                    actors.add((Mob) restored);
                }
                if (scenario == 1) require(actors.getFirst().getTarget() != null
                        && actors.getFirst().getTarget().getUniqueId().equals(ids.get(1)), "Slime target lost immediately on restore");
                if (scenario == 0) onlyAvoidance((Creeper) actors.getFirst());
                after = true;
                reset();
                plugin.getLogger().info("MOB_BEHAVIOR_RESTORED: " + name());
            } else {
                plugin.getLogger().info("MOB_BEHAVIOR_PASSED: " + name());
                clear();
                if (scenario == 2) {
                    cancel(); world.removePluginChunkTickets(plugin);
                    plugin.getLogger().info("MOB_BEHAVIOR_SMOKE_PASSED");
                    finish.accept(null);
                } else next();
            }
        } catch (Throwable error) {
            cancel(); world.removePluginChunkTickets(plugin); finish.accept(error);
        }
    }

    private boolean ready() {
        if (scenario == 0) return actors.getFirst().getLocation().distanceSquared(actors.get(1).getLocation()) >= 36;
        if (scenario == 1) return actors.getFirst().getTarget() != null
                && actors.getFirst().getTarget().getUniqueId().equals(actors.get(1).getUniqueId())
                && actors.getFirst().getLocation().distanceSquared(actors.get(1).getLocation()) < 64;
        return world.getNearbyEntities(bounds()).stream().anyMatch(e -> e.getType() == EntityType.IRON_GOLEM);
    }

    private void reset() {
        if (scenario == 2) {
            world.getNearbyEntities(bounds()).stream().filter(e -> e.getType() == EntityType.IRON_GOLEM).forEach(Entity::remove);
            for (var actor : actors) if (actor instanceof Villager villager) {
                villager.setMemory(MemoryKey.LAST_SLEPT, world.getGameTime());
                villager.setMemory(MemoryKey.GOLEM_DETECTED_RECENTLY, null);
            }
        }
        for (int i = 0; i < actors.size(); i++) {
            var actor = actors.get(i);
            actor.getPathfinder().stopPathfinding();
            actor.teleport(starts.get(i)); actor.setVelocity(new Vector());
        }
        elapsed = 0; control = scenario == 0;
        if (control) {
            world.getBlockAt(x + 48, 300, 16).setType(Material.STONE, false);
            actors.get(1).teleport(new Location(world, x + 48.5, 301, 16.5));
        }
    }

    private void onlyAvoidance(Creeper creeper) {
        var goals = plugin.getServer().getMobGoals();
        var all = new ArrayList<>(goals.getAllGoals(creeper));
        require(all.stream().anyMatch(goal -> goal.getKey().getNamespacedKey().getKey().equals("avoid_entity")), "No native avoidance goal");
        for (var goal : all) if (!goal.getKey().getNamespacedKey().getKey().equals("avoid_entity")) goals.removeGoal(creeper, goal);
    }

    private BoundingBox bounds() { return new BoundingBox(x - 1, 299, -1, x + 33, 307, 33); }
    private void clear() { world.getNearbyEntities(bounds()).forEach(Entity::remove); }
    private String name() { return switch (scenario) { case 0 -> "creeper-cat"; case 1 -> "slime-golem"; default -> "villager-zombie-iron"; }; }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
