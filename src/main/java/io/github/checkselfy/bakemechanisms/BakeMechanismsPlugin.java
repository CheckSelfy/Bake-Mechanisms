package io.github.checkselfy.bakemechanisms;

import io.github.checkselfy.bakemechanisms.marking.FarmRegistry;
import io.github.checkselfy.bakemechanisms.marking.MarkingController;
import io.github.checkselfy.bakemechanisms.marking.PaperOutputResolver;
import io.github.checkselfy.bakemechanisms.profiling.PaperProfilingWorld;
import io.github.checkselfy.bakemechanisms.profiling.ProfilingProtection;
import io.github.checkselfy.bakemechanisms.profiling.ProfilingService;
import io.github.checkselfy.bakemechanisms.profiling.ProfilingSettings;
import io.github.checkselfy.bakemechanisms.stasis.FileSnapshotStore;
import io.github.checkselfy.bakemechanisms.stasis.PaperStasisWorld;
import io.github.checkselfy.bakemechanisms.stasis.StasisCommands;
import io.github.checkselfy.bakemechanisms.stasis.StasisProtection;
import io.github.checkselfy.bakemechanisms.stasis.StasisService;
import java.io.IOException;
import java.util.Objects;
import org.bukkit.plugin.java.JavaPlugin;

public final class BakeMechanismsPlugin extends JavaPlugin {
    private ProfilingService profiling;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        long maxVolume = getConfig().getLong("max-mechanism-volume", FarmRegistry.DEFAULT_MAX_VOLUME);
        if (maxVolume < 1) {
            getLogger().severe("max-mechanism-volume must be positive; plugin disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        ProfilingSettings settings;
        try {
            var defaults = ProfilingSettings.DEFAULT;
            settings = new ProfilingSettings(
                    getConfig().getInt("profiling.snapshot-interval", defaults.snapshotInterval()),
                    getConfig().getLong("profiling.min-useful-ticks", defaults.minUsefulTicks()),
                    getConfig().getBoolean("profiling.reject-short-useful-segment",
                            defaults.rejectShortUsefulSegment()));
        } catch (IllegalArgumentException error) {
            getLogger().severe("Invalid profiling settings: " + error.getMessage() + " Plugin disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        var registry = new FarmRegistry(maxVolume);
        var resolver = new PaperOutputResolver();
        var stasis = new StasisService(registry, new PaperStasisWorld(this, resolver),
                new FileSnapshotStore(getDataFolder().toPath().resolve("farms"), getServer().getMinecraftVersion()));
        try {
            stasis.recover();
        } catch (IOException | RuntimeException error) {
            getLogger().log(java.util.logging.Level.SEVERE, "Cannot recover active snapshots; plugin disabled.", error);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        profiling = new ProfilingService(registry, new PaperProfilingWorld(this, resolver), settings);
        var marking = new MarkingController(this, registry, resolver, profiling);
        var commands = new StasisCommands(marking, registry, stasis, getLogger(),
                task -> getServer().getScheduler().runTaskLater(this, task, 1L));
        var command = Objects.requireNonNull(getCommand("bake"), "bake command missing in plugin.yml");
        command.setExecutor(commands);
        command.setTabCompleter(commands);
        getServer().getPluginManager().registerEvents(marking, this);
        getServer().getPluginManager().registerEvents(new ProfilingProtection(profiling), this);
        getServer().getPluginManager().registerEvents(new StasisProtection(stasis), this);
        getServer().getScheduler().runTaskTimer(this, profiling::tick, 1L, 1L);
        getLogger().info("Bake Mechanisms enabled");
    }

    @Override
    public void onDisable() {
        // Unfinished profiling never survives a restart: drop protection and chunk tickets.
        if (profiling != null) profiling.cancelAll();
        getLogger().info("Bake Mechanisms disabled");
    }
}
