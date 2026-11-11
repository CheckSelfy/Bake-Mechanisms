package io.github.checkselfy.bakemechanisms.lab;

import io.github.checkselfy.bakemechanisms.storage.FarmStorage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Automatic commands against the same imported structures used by manual players. */
public final class FarmLabReviewPlugin extends JavaPlugin {
    private FarmLabPlugin lab;
    private FarmStorage storage;
    private Player creator;
    private Player other;
    private int phase;
    private int ticks;
    private final Map<String, UUID> ids = new LinkedHashMap<>();
    private final Map<String, Map<Material, Integer>> baseline = new HashMap<>();
    private final Map<String, Map<String, String>> blockStates = new HashMap<>();
    private final Map<String, Map<String, ItemStack[]>> inventories = new HashMap<>();

    @Override public void onEnable() {
        lab = (FarmLabPlugin) getServer().getPluginManager().getPlugin("BakeFarmLab");
        storage = new FarmStorage(getServer().getPluginManager().getPlugin("BakeMechanisms").getDataFolder().toPath().resolve("farms"));
        getServer().getScheduler().runTaskTimer(this, () -> {
            try { tick(); }
            catch (Throwable error) {
                getLogger().log(Level.SEVERE, "FARM_LAB_REVIEW_FAILED phase=" + phase, error);
                phase = 99;
                getServer().shutdown();
            }
        }, 20L, 1L);
    }

    private void tick() {
        ticks++;
        if (phase == 0) {
            creator = getServer().getPlayerExact("ReviewCreator");
            other = getServer().getPlayerExact("ReviewOther");
            if (creator == null || other == null) { check(ticks < 2400, "clients did not join"); return; }
            check(!creator.isOp() && !other.isOp(), "clients must be non-OP");
            creator.setInvulnerable(true); other.setInvulnerable(true);
            creator.teleport(new Location(lab.world(), -3.5, 64, 4.5));
            other.teleport(new Location(lab.world(), 61.5, 64, 8.5));
            getServer().dispatchCommand(getServer().getConsoleSender(), "tick rate 100");
            next(1);
        } else if (phase == 1) {
            if (ticks % 4000 == 0) pass("WARMUP", "elapsed=" + ticks);
            if (ticks < 12000) return;
            boolean productive = lab.farms().stream().allMatch(f -> counts(f).getOrDefault(Material.valueOf(f.product()), 0) > 0);
            if (!productive && ticks < 36000) {
                if (ticks % 4000 == 0) pass("WAIT_NATURAL_OUTPUT", "cane batches are random; elapsed=" + ticks);
                return;
            }
            check(lab.outsideWaterCount() == 0, "exterior water returned during warmup");
            // First prove ordinary random-tick production; then accelerate crop growth for a
            // functional 3600-tick profile of the original, infrequently triggered cane batches.
            lab.world().setGameRule(org.bukkit.GameRules.RANDOM_TICK_SPEED, 30);
            pass("FAST_GROWTH", "profiling randomTickSpeed=30; warmup used 3; functional test, not a vanilla yield benchmark");
            for (var farm : lab.farms()) {
                check(counts(farm).getOrDefault(Material.valueOf(farm.product()), 0) > 0,
                        "no natural output during warmup: " + farm.id() + " " + counts(farm));
                pass("NATURAL_OUTPUT", farm.id() + " ticks=" + ticks + " output=" + counts(farm));
                output(farm).clear();
                register(farm);
                command("profile --id " + ids.get(farm.id()));
            }
            next(2);
        } else if (phase == 2) {
            if (ticks < 3600) return;
            for (var farm : lab.farms()) {
                int[] moving = {0};
                eachBlock(farm, b -> { if (b.getType() == Material.MOVING_PISTON) moving[0]++; });
                if (moving[0] != 0) { check(ticks < 3620, "piston never settled"); return; }
            }
            for (var farm : lab.farms()) {
                var stored = stored(farm);
                check(stored.farm().state().name().equals("READY"), "profile failed: " + farm.id());
                check(stored.farm().profile().ratesPer20Ticks().getOrDefault(Material.valueOf(farm.product()), 0.0) > 0,
                        "expected product absent from profile");
                pass("PROFILE", farm.id() + " " + stored.farm().profile());
                baseline.put(farm.id(), counts(farm));
                var blocks = new LinkedHashMap<String, String>();
                var items = new LinkedHashMap<String, ItemStack[]>();
                eachBlock(farm, block -> {
                    String key = key(block);
                    blocks.put(key, block.getBlockData().getAsString());
                    if (block.getState() instanceof Container container) {
                        var content = container.getInventory().getContents();
                        for (int i = 0; i < content.length; i++) if (content[i] != null) content[i] = content[i].clone();
                        items.put(key, content);
                    }
                });
                blockStates.put(farm.id(), blocks);
                inventories.put(farm.id(), items);
                command("stasis --id " + ids.get(farm.id()));
                check(stored(farm).farm().state().name().equals("STASIS"), "stasis refused " + farm.id());
                eachBlock(farm, b -> check(b.getType() == Material.BEDROCK, "incomplete filler"));
            }
            next(3);
        } else if (phase == 3) {
            if (ticks < 2000) return;
            for (var farm : lab.farms()) {
                var stored = stored(farm);
                var progress = stored.emulation().orElseThrow();
                var current = counts(farm);
                for (var rate : stored.farm().profile().ratesPer20Ticks().entrySet()) {
                    var total = BigDecimal.valueOf(rate.getValue()).multiply(BigDecimal.valueOf(100));
                    int expected = total.setScale(0, RoundingMode.FLOOR).intValueExact();
                    int actual = current.getOrDefault(rate.getKey(), 0) - baseline.get(farm.id()).getOrDefault(rate.getKey(), 0)
                            + progress.outputs().getFirst().buffer().getOrDefault(rate.getKey(), 0);
                    check(expected == actual, "production mismatch " + farm.id());
                    check(total.remainder(BigDecimal.ONE).compareTo(progress.outputs().getFirst().fractions().get(rate.getKey())) == 0, "fraction mismatch");
                    pass("ACCOUNTING", farm.id() + " ticks=2000 type=" + rate.getKey() + " produced=" + actual
                            + " fraction=" + progress.outputs().getFirst().fractions().get(rate.getKey()));
                }
                command("restore --id " + ids.get(farm.id()));
                check(stored(farm).farm().state().name().equals("NEEDS_PROFILING"), "restore failed " + farm.id());
                eachBlock(farm, block -> {
                    check(block.getBlockData().getAsString().equals(blockStates.get(farm.id()).get(key(block))),
                            "restored block differs at " + key(block) + " expected=" + blockStates.get(farm.id()).get(key(block))
                                    + " actual=" + block.getBlockData().getAsString());
                    if (block.getState() instanceof Container container) {
                        check(java.util.Arrays.equals(container.getInventory().getContents(), inventories.get(farm.id()).get(key(block))),
                                "restored inventory differs at " + key(block));
                    }
                });
                pass("RESTORE", farm.id() + " all block states and internal inventories restored; old profile invalidated");
            }
            next(4);
        } else if (phase == 4 && ticks >= 200) {
            check(lab.outsideWaterCount() == 0, "exterior water returned after restoration");
            for (String id : new String[]{"cactus", "cane"}) {
                check(getServer().dispatchCommand(getServer().getConsoleSender(), "farmlab compare " + id + " 40"), "comparison command missing");
            }
            next(5);
        } else if (phase == 5 && ticks >= 60) {
            var directory = lab.getDataFolder().toPath().resolve("comparisons");
            try (var files = java.nio.file.Files.list(directory)) {
                check(files.count() == 2, "pair comparison records missing");
            } catch (java.io.IOException error) { throw new IllegalStateException(error); }
            pass("PASSED", "four extracted farms in identical initial pairs; production/accounting/restore verified; no exterior water");
            phase = 99; getServer().shutdown();
        }
    }

    private void register(FarmLabPlugin.Farm farm) {
        creator.getInventory().clear(); command("wand"); creator.getInventory().setHeldItemSlot(0);
        interact(farm.destination(), Action.LEFT_CLICK_BLOCK, false);
        interact(farm.maximum(), Action.RIGHT_CLICK_BLOCK, false);
        interact(farm.output(), Action.RIGHT_CLICK_BLOCK, true);
        command("register");
        var saved = storage.loadAll().stream().filter(f -> f.farm().mechanism().min().x() == farm.destination()[0]).findFirst().orElseThrow();
        ids.put(farm.id(), saved.farm().id());
    }

    private void interact(int[] p, Action action, boolean sneaking) {
        creator.setSneaking(sneaking);
        getServer().getPluginManager().callEvent(new PlayerInteractEvent(creator, action, creator.getInventory().getItemInMainHand(),
                lab.world().getBlockAt(p[0], p[1], p[2]), BlockFace.UP, EquipmentSlot.HAND));
        creator.setSneaking(false);
    }

    private FarmStorage.StoredFarm stored(FarmLabPlugin.Farm farm) {
        return storage.loadAll().stream().filter(f -> f.farm().id().equals(ids.get(farm.id()))).findFirst().orElseThrow();
    }
    private org.bukkit.inventory.Inventory output(FarmLabPlugin.Farm farm) {
        var p = farm.output(); return ((Container) lab.world().getBlockAt(p[0], p[1], p[2]).getState()).getInventory();
    }
    private Map<Material, Integer> counts(FarmLabPlugin.Farm farm) {
        var result = new java.util.EnumMap<Material, Integer>(Material.class);
        for (var item : output(farm).getContents()) if (item != null) result.merge(item.getType(), item.getAmount(), Integer::sum);
        return result;
    }
    private void eachBlock(FarmLabPlugin.Farm farm, java.util.function.Consumer<Block> consumer) {
        var low = farm.destination(); var high = farm.maximum();
        for (int x = low[0]; x <= high[0]; x++) for (int y = low[1]; y <= high[1]; y++) for (int z = low[2]; z <= high[2]; z++)
            consumer.accept(lab.world().getBlockAt(x, y, z));
    }
    private static String key(Block block) { return block.getX() + "," + block.getY() + "," + block.getZ(); }
    private void command(String args) { check(creator.performCommand("bake " + args), "unknown bake command"); }
    private void next(int phase) { this.phase = phase; ticks = 0; }
    private void pass(String label, String details) { getLogger().info("FARM_LAB_REVIEW_" + label + " tick=" + getServer().getCurrentTick() + " " + details); }
    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
