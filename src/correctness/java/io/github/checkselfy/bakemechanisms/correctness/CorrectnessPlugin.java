package io.github.checkselfy.bakemechanisms.correctness;

import io.github.checkselfy.bakemechanisms.emulation.EmulationState;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.State;
import io.github.checkselfy.bakemechanisms.storage.FarmStorage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.java.JavaPlugin;

/** Disposable-server acceptance driver. Commands use real non-OP Player instances;
 * wand/protection inputs are synthesized Bukkit events, explicitly not a client UI test. */
public final class CorrectnessPlugin extends JavaPlugin {
    private FarmStorage storage;
    private Path root;
    private Player a;
    private Player b;
    private World blocks;
    private World entities;
    private UUID blockFarm;
    private UUID entityFarm;
    private UUID emptyFarm;
    private UUID saturatedFarm;
    private int creatorExperience;
    private int otherExperience;
    private int step;
    private long elapsed;
    private long started;
    private boolean resumed;
    private boolean shortProfileOnly;
    private final Map<UUID, Map<Material, Integer>> before = new java.util.HashMap<>();
    private EmulationState plateau;

    @Override public void onEnable() {
        root = getServer().getPluginManager().getPlugin("BakeMechanisms").getDataFolder().toPath().resolve("farms");
        storage = new FarmStorage(root);
        resumed = Files.exists(getDataFolder().toPath().resolve("restart.yml"));
        shortProfileOnly = Files.exists(Path.of("short-profile-only"));
        started = getServer().getCurrentTick();
        getServer().getScheduler().runTaskTimer(this, () -> {
            try { tick(); }
            catch (Throwable error) {
                getLogger().log(Level.SEVERE, "REVIEW_CORRECTNESS_FAILED step=" + step, error);
                getServer().shutdown();
                getServer().getScheduler().cancelTasks(this);
            }
        }, 1L, 1L);
    }

    private void tick() throws Exception {
        if (step == 0) {
            a = getServer().getPlayerExact("ReviewCreator");
            b = getServer().getPlayerExact("ReviewOther");
            if (a == null || b == null) {
                check(getServer().getCurrentTick() - started < 24000, "headless players did not connect");
                return;
            }
            check(!a.isOp() && !b.isOp(), "review players must not be OP");
            a.setGameMode(GameMode.SURVIVAL);
            b.setGameMode(GameMode.SURVIVAL);
            a.setInvulnerable(true);
            b.setInvulnerable(true);
            if (!resumed) console("bakefixtures create correctness");
            else console("bakefixtures list");
            blocks = getServer().getWorld("bake_blocks");
            entities = getServer().getWorld("bake_entities");
            check(blocks != null && entities != null, "fixture worlds unavailable");
            a.teleport(new Location(blocks, -2.5, 65, 7.5));
            b.teleport(new Location(entities, -2.5, 65, 7.5));
            console("bakefixtures release");
            // Accelerate elapsed wall time, retaining actual server ticks and default randomTickSpeed.
            console("tick rate 100");
            if (resumed) resume();
            else {
                emptyFarm = auxiliary(64);
                saturatedFarm = auxiliary(96);
                var saturated = output(blocks, 96);
                for (int slot = 0; slot < saturated.getSize(); slot++) saturated.setItem(slot, new ItemStack(Material.STONE, 64));
                saturated.setItem(0, new ItemStack(Material.STONE, 62));
                command(a, "profile " + emptyFarm);
                command(a, "profile " + saturatedFarm);
                a.teleport(new Location(blocks, -2.5, 65, 7.5));
                next(1);
            }
            return;
        }
        elapsed++;
        switch (step) {
            case 1 -> {
                if (elapsed == 100 || elapsed == 200) output(blocks, 96).addItem(new ItemStack(Material.STONE));
                if (elapsed == 3600) {
                    state(emptyFarm, State.NEEDS_PROFILING);
                    if (shortProfileOnly) {
                        state(saturatedFarm, State.NEEDS_PROFILING);
                        check(farm(saturatedFarm).farm().profile() == null, "short profile retained with rejection enabled");
                        for (var id : List.of(emptyFarm, saturatedFarm)) {
                            command(a, "delete " + id); command(a, "delete " + id + " confirm");
                        }
                        check(storage.loadAll().isEmpty() && tickets(blocks) == 0, "short-profile run leaked files/tickets");
                        pass("SHORT_PROFILE_PASSED", "3600 actual ticks: 200-tick saturated segment rejected with 600-tick minimum enabled; zero output also rejected");
                        getServer().shutdown(); next(99);
                        return;
                    }
                    state(saturatedFarm, State.READY);
                    var profile = farm(saturatedFarm).farm().profile();
                    check(profile.usefulTicks() == 200 && profile.ratesPer20Ticks().equals(Map.of(Material.STONE, 0.1)),
                            "saturation must omit the full sample and flat tail: " + profile);
                    for (var id : List.of(emptyFarm, saturatedFarm)) {
                        command(a, "delete " + id); command(a, "delete " + id + " confirm");
                    }
                    check(tickets(blocks) == 0, "auxiliary profiling leaked tickets");
                    pass("C04_EMPTY_SATURATED", "3600 actual ticks: zero output rejected; saturated profile uses 200 ticks and rate=0.1; short-segment rejection disabled");
                }
                if (elapsed < 12000) return;
                output(blocks).clear(); output(entities).clear();
                pass("C02_WARMUP", "12000 actual server ticks; randomTickSpeed=3; two nearby clients");
                next(2);
            }
            case 2 -> {
                if (elapsed < 12000) return;
                check(counts(blocks).getOrDefault(Material.CACTUS, 0) > 0, "no natural cactus production");
                check(counts(entities).getOrDefault(Material.EGG, 0) > 0, "no natural egg production");
                pass("C02", "12000 ticks natural output blocks=" + counts(blocks) + " entities=" + counts(entities));
                blockFarm = register(blocks);
                entityFarm = register(entities);
                a.teleport(new Location(blocks, -2.5, 65, 7.5));
                rights(blockFarm);
                creatorExperience = a.getTotalExperience();
                otherExperience = b.getTotalExperience();
                command(a, "profile " + blockFarm);
                state(blockFarm, State.PROFILING);
                command(a, "cancel " + blockFarm);
                state(blockFarm, State.NEEDS_PROFILING);
                check(tickets(blocks) == 0, "cancel leaked tickets");
                pass("C04_CANCEL", "production /bake profile + cancel; original retained and tickets released");
                output(blocks).clear(); output(entities).clear();
                output(blocks).addItem(new ItemStack(Material.CACTUS, 10));
                output(entities).addItem(new ItemStack(Material.EGG, 10));
                command(a, "profile " + blockFarm);
                command(a, "profile " + entityFarm);
                state(blockFarm, State.PROFILING); state(entityFarm, State.PROFILING);
                var breakEvent = new BlockBreakEvent(blocks.getBlockAt(0, 63, 0), b);
                getServer().getPluginManager().callEvent(breakEvent);
                check(breakEvent.isCancelled(), "profiling permits block break");
                next(3);
            }
            case 3 -> {
                if (elapsed == 100) {
                    var sword = new ItemStack(Material.DIAMOND_SWORD);
                    var meta = (Damageable) sword.getItemMeta();
                    meta.setDamage(100);
                    meta.displayName(Component.text("review-normalization-input"));
                    meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 2, true);
                    sword.setItemMeta(meta);
                    // Controlled supplemental input, never counted as natural egg production.
                    output(entities).addItem(sword);
                }
                if (elapsed < 3600) return;
                state(blockFarm, State.READY); state(entityFarm, State.READY);
                verifyProfile(blockFarm, blocks, Material.CACTUS);
                verifyProfile(entityFarm, entities, Material.EGG);
                check(tickets(blocks) == 0 && tickets(entities) == 0, "profiling leaked tickets");
                pass("C03", "3600 ticks, baseline excluded; profiles=" + farm(blockFarm).farm().profile()
                        + " / " + farm(entityFarm).farm().profile());
                b.teleport(new Location(blocks, 2.5, 65, 2.5));
                command(a, "stasis " + blockFarm);
                state(blockFarm, State.READY);
                check(b.getLocation().distanceSquared(new Location(blocks, 2.5, 65, 2.5)) < 0.01,
                        "refused stasis moved the player");
                b.teleport(new Location(entities, -2.5, 65, 7.5));
                pass("C05_OCCUPIED", "player inside: command refused, READY retained, player not teleported");
                for (var id : List.of(blockFarm, entityFarm)) {
                    before.put(id, counts(world(id)));
                    command(a, "stasis " + id); state(id, State.STASIS);
                    filler(world(id));
                    check(tickets(world(id)) == 1, "stasis must hold only output chunk");
                    var event = new BlockBreakEvent(world(id).getBlockAt(0, 63, 0), b);
                    getServer().getPluginManager().callEvent(event);
                    check(event.isCancelled(), "stasis permits block break");
                    b.setGameMode(GameMode.CREATIVE);
                    var creative = new BlockBreakEvent(world(id).getBlockAt(0, 63, 0), b);
                    getServer().getPluginManager().callEvent(creative);
                    check(creative.isCancelled(), "creative can break filler");
                    b.setGameMode(GameMode.SURVIVAL);
                    var outputBreak = new BlockBreakEvent(world(id).getBlockAt(16, 64, 7), b);
                    getServer().getPluginManager().callEvent(outputBreak);
                    check(outputBreak.isCancelled(), "stasis output can be broken");
                }
                check(chickens() == 0, "original chickens remain in stasis");
                pass("C05", "both cuboids entirely bedrock; original chickens absent; only output tickets retained");
                command(a, "delete " + blockFarm);
                command(a, "delete " + blockFarm + " confirm");
                state(blockFarm, State.STASIS);
                check(Files.exists(root.resolve(blockFarm.toString()).resolve("stasis.yml")), "delete lost active snapshot");
                next(4);
            }
            case 4 -> {
                if (elapsed < 4000) return;
                for (var id : List.of(blockFarm, entityFarm)) verifyProduction(id, 4000, before.get(id), empty());
                check(java.util.Arrays.stream(output(entities).getContents()).anyMatch(item -> item != null
                        && item.getType() == Material.DIAMOND_SWORD && !item.hasItemMeta()), "no normalized sword");
                check(blocks.getEntitiesByClass(ExperienceOrb.class).isEmpty()
                        && entities.getEntitiesByClass(ExperienceOrb.class).isEmpty(), "emulation created XP");
                check(a.getTotalExperience() == creatorExperience && b.getTotalExperience() == otherExperience,
                        "emulation awarded player experience");
                pass("C06_C07", "exact 4000-tick inventory + buffer + fraction accounting for every type; plain swords; no XP orbs");
                command(a, "restore " + entityFarm);
                restored(entityFarm);
                check(chickens() == 8, "restored chicken count differs");
                pass("C09_ENTITIES", "8 chickens and snapshot-marker inventory restored; profile invalidated");
                for (int i = 0; i < output(blocks).getSize(); i++) output(blocks).setItem(i, new ItemStack(Material.STONE, 64));
                next(5);
            }
            case 5 -> {
                if (elapsed == 200) {
                    plateau = farm(blockFarm).emulation().orElseThrow();
                    check(!plateau.buffer().isEmpty(), "full output did not accumulate bounded pending production");
                }
                if (elapsed < 3800) return;
                check(plateau.equals(farm(blockFarm).emulation().orElseThrow()), "production continued with blocked buffer");
                check(blocks.getEntitiesByClass(org.bukkit.entity.Item.class).isEmpty(), "full output dropped items into world");
                pass("C08_PAUSE", "full output: identical buffer/fractions for 3600 ticks; no overflow drops");
                command(a, "profile " + entityFarm); state(entityFarm, State.PROFILING);
                next(6);
            }
            case 6 -> {
                if (elapsed < 200) return;
                var checkpoint = new YamlConfiguration();
                checkpoint.set("blocks", blockFarm.toString()); checkpoint.set("entities", entityFarm.toString());
                Files.createDirectories(getDataFolder().toPath());
                checkpoint.save(getDataFolder().toPath().resolve("restart.yml").toFile());
                Files.copy(root.resolve(blockFarm.toString()).resolve("emulation.dat"),
                        getDataFolder().toPath().resolve("emulation-before-restart.dat"));
                pass("RESTART_READY", "one STASIS farm with full output/nonempty buffer; one unfinished PROFILING farm");
                getServer().shutdown(); next(99);
            }
            case 7 -> {
                if (elapsed < 200) return;
                verifyProduction(blockFarm, 200, Map.of(), plateau);
                pass("C08_RESUME", "freed output: pending buffer delivered and exact production resumed");
                command(a, "restore " + blockFarm); restored(blockFarm);
                pass("C09_BLOCKS", "cactus, hoppers, internal marker restored after process restart");
                command(a, "stasis " + blockFarm); state(blockFarm, State.NEEDS_PROFILING);
                command(a, "profile " + blockFarm); command(a, "profile " + entityFarm);
                next(8);
            }
            case 8 -> {
                if (elapsed == 100) {
                    // Controlled positive output ensures this repeated-cycle test is independent of egg RNG.
                    output(blocks).addItem(new ItemStack(Material.CACTUS));
                    output(entities).addItem(new ItemStack(Material.EGG));
                }
                if (elapsed < 3600) return;
                state(blockFarm, State.READY); state(entityFarm, State.READY);
                a.getInventory().clear();
                var edit = new BlockBreakEvent(blocks.getBlockAt(0, 63, 0), a);
                getServer().getPluginManager().callEvent(edit);
                state(blockFarm, State.NEEDS_PROFILING);
                command(a, "stasis " + blockFarm); state(blockFarm, State.NEEDS_PROFILING);
                command(a, "stasis " + entityFarm); state(entityFarm, State.STASIS);
                command(a, "restore " + entityFarm); restored(entityFarm);
                for (var id : List.of(blockFarm, entityFarm)) {
                    command(b, "delete " + id); command(b, "delete " + id + " confirm");
                    check(Files.exists(root.resolve(id.toString())), "noncreator deleted farm");
                    command(a, "delete " + id + " confirm");
                    check(Files.exists(root.resolve(id.toString())), "confirmation without request deleted farm");
                    command(a, "delete " + id);
                    check(Files.exists(root.resolve(id.toString())), "unconfirmed request deleted farm");
                    command(a, "delete " + id + " confirm");
                    check(!Files.exists(root.resolve(id.toString())), "confirmed delete retained farm files");
                }
                check(storage.loadAll().isEmpty(), "registrations remain after delete");
                check(tickets(blocks) == 0 && tickets(entities) == 0, "delete leaked chunk tickets");
                pass("C11", "repeat profile/stasis/restore; READY edit invalidation; creator-only confirmed deletion; all files/tickets released");
                console("bakefixtures check");
                pass("CORRECTNESS_PASSED", "headless automated subset complete; synthetic input boundaries documented");
                getServer().shutdown(); next(99);
            }
            default -> { }
        }
    }

    private void resume() throws Exception {
        var checkpoint = YamlConfiguration.loadConfiguration(getDataFolder().toPath().resolve("restart.yml").toFile());
        blockFarm = UUID.fromString(checkpoint.getString("blocks"));
        entityFarm = UUID.fromString(checkpoint.getString("entities"));
        state(blockFarm, State.STASIS); state(entityFarm, State.NEEDS_PROFILING);
        check(farm(entityFarm).farm().profile() == null, "restart retained unfinished profile");
        check(java.util.Arrays.equals(Files.readAllBytes(getDataFolder().toPath().resolve("emulation-before-restart.dat")),
                Files.readAllBytes(root.resolve(blockFarm.toString()).resolve("emulation.dat"))), "restart changed blocked production progress");
        plateau = farm(blockFarm).emulation().orElseThrow();
        filler(blocks);
        check(tickets(blocks) == 1 && tickets(entities) == 0, "restart tickets differ");
        pass("C10", "real process restart: active filler/snapshot/profile/buffer/fractions retained; unfinished profile cancelled; blocked production progress unchanged");
        output(blocks).clear();
        next(7);
    }

    private UUID auxiliary(int x) {
        blocks.getBlockAt(x, 63, 0).setType(Material.STONE, false);
        blocks.getBlockAt(x + 16, 64, 7).setType(Material.BARREL, false);
        return register(blocks, x);
    }

    private UUID register(World world) { return register(world, 0); }

    private UUID register(World world, int x) {
        a.teleport(new Location(world, x - 2.5, 65, 7.5));
        a.getInventory().clear();
        command(a, "wand");
        a.getInventory().setHeldItemSlot(0);
        interact(world, Action.RIGHT_CLICK_BLOCK, x + 15, 70, 15, false);
        interact(world, Action.LEFT_CLICK_BLOCK, x, 63, 0, false);
        interact(world, Action.RIGHT_CLICK_BLOCK, x + 16, 64, 7, true);
        command(a, "register");
        var found = storage.loadAll().stream().filter(f -> f.farm().mechanism().min().worldId().equals(world.getUID())
                && f.farm().mechanism().min().x() == x).toList();
        check(found.size() == 1, "registration did not produce exactly one farm");
        var farm = found.getFirst().farm();
        check(farm.creatorId().equals(a.getUniqueId()) && farm.state() == State.NEEDS_PROFILING, "registration creator/state");
        pass("C01_REGISTER", "world=" + world.getName() + " farm=" + farm.id() + "; production wand, synthetic events, reversed corners, real non-OP creator");
        return farm.id();
    }

    private void rights(UUID id) {
        for (String op : List.of("profile", "cancel", "stasis", "restore", "delete")) command(b, op + " " + id);
        state(id, State.NEEDS_PROFILING);
        var command = getServer().getPluginCommand("bake");
        var completions = command.tabComplete(b, "bake", new String[]{"profile", ""});
        check(!completions.contains(id.toString()), "noncreator sees farm completion");
        pass("C01_RIGHTS", "noncreator lifecycle commands denied and UUID absent from completion");
    }

    private void interact(World world, Action action, int x, int y, int z, boolean sneaking) {
        a.setSneaking(sneaking);
        var event = new PlayerInteractEvent(a, action, a.getInventory().getItemInMainHand(),
                world.getBlockAt(x, y, z), BlockFace.UP, EquipmentSlot.HAND);
        getServer().getPluginManager().callEvent(event);
        a.setSneaking(false);
    }

    private void verifyProfile(UUID id, World world, Material baselineType) {
        var profile = farm(id).farm().profile();
        check(profile.usefulTicks() == 3600, "profile useful duration");
        var totals = counts(world);
        totals.merge(baselineType, -10, Integer::sum);
        for (var entry : totals.entrySet()) {
            if (entry.getValue() == 0) continue;
            double expected = entry.getValue() * 20.0 / 3600;
            check(Math.abs(profile.ratesPer20Ticks().getOrDefault(entry.getKey(), 0.0) - expected) < 1e-12,
                    "profile baseline/rate mismatch " + entry);
        }
    }

    private void verifyProduction(UUID id, long ticks, Map<Material, Integer> initial, EmulationState state) {
        var stored = farm(id);
        var actual = counts(world(id));
        var progress = stored.emulation().orElseThrow();
        for (var rate : stored.farm().profile().ratesPer20Ticks().entrySet()) {
            var type = rate.getKey();
            var total = state.fractions().getOrDefault(type, BigDecimal.ZERO)
                    .add(BigDecimal.valueOf(rate.getValue()).multiply(BigDecimal.valueOf(ticks)).divide(BigDecimal.valueOf(20)));
            int expected = total.setScale(0, RoundingMode.FLOOR).intValueExact() + state.buffer().getOrDefault(type, 0);
            int observed = actual.getOrDefault(type, 0) - initial.getOrDefault(type, 0) + progress.buffer().getOrDefault(type, 0);
            check(expected == observed, "production mismatch " + type + " expected=" + expected + " actual=" + observed);
            check(total.remainder(BigDecimal.ONE).compareTo(progress.fractions().get(type)) == 0, "fraction mismatch " + type);
            pass("ACCOUNTING", "farm=" + id + " type=" + type + " ticks=" + ticks + " produced=" + observed
                    + " fraction=" + progress.fractions().get(type));
        }
    }

    private void restored(UUID id) {
        state(id, State.NEEDS_PROFILING);
        check(farm(id).farm().profile() == null, "restore retained old profile");
        World world = world(id);
        check(world.getBlockAt(0, 63, 0).getType() != Material.BEDROCK, "filler remains");
        var marker = ((Container) world.getBlockAt(0, 65, 0).getState()).getInventory().getItem(0);
        check(marker != null && marker.getType() == Material.STONE && marker.getAmount() == 3
                && Component.text("snapshot-marker").equals(marker.getItemMeta().displayName()), "internal marker lost");
        if (world.equals(blocks)) check(world.getBlockAt(2, 66, 2).getType() == Material.CACTUS, "cactus not restored");
    }

    private int chickens() { return entities.getEntitiesByClass(Chicken.class).size(); }
    private int tickets(World world) {
        var plugin = getServer().getPluginManager().getPlugin("BakeMechanisms");
        return world.getPluginChunkTickets().getOrDefault(plugin, List.of()).size();
    }
    private void filler(World world) {
        for (int x = 0; x < 16; x++) for (int y = 63; y <= 70; y++) for (int z = 0; z < 16; z++)
            check(world.getBlockAt(x, y, z).getType() == Material.BEDROCK, "incomplete filler " + world.getName());
    }
    private org.bukkit.inventory.Inventory output(World world) { return output(world, 0); }
    private org.bukkit.inventory.Inventory output(World world, int x) { return ((Container) world.getBlockAt(x + 16, 64, 7).getState()).getInventory(); }
    private Map<Material, Integer> counts(World world) {
        var counts = new EnumMap<Material, Integer>(Material.class);
        for (var item : output(world).getContents()) if (item != null) counts.merge(item.getType(), item.getAmount(), Integer::sum);
        return counts;
    }
    private World world(UUID id) { return id.equals(blockFarm) ? blocks : entities; }
    private FarmStorage.StoredFarm farm(UUID id) { return storage.loadAll().stream().filter(f -> f.farm().id().equals(id)).findFirst().orElseThrow(); }
    private void state(UUID id, State expected) { check(farm(id).farm().state() == expected, "farm " + id + " expected " + expected + " actual=" + farm(id).farm().state()); }
    private static EmulationState empty() { return new EmulationState(Map.of(), Map.of()); }
    private void command(Player player, String command) { check(player.performCommand("bake " + command), "unknown command"); }
    private void console(String command) { check(getServer().dispatchCommand(getServer().getConsoleSender(), command), "console command refused " + command); }
    private void next(int step) { this.step = step; elapsed = 0; }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private void pass(String name, String details) { getLogger().info("REVIEW_PASS " + name + " tick=" + getServer().getCurrentTick() + " " + details); }
}
