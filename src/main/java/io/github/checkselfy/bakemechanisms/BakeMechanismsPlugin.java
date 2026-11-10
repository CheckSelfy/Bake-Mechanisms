package io.github.checkselfy.bakemechanisms;

import io.github.checkselfy.bakemechanisms.emulation.EmulationSettings;
import io.github.checkselfy.bakemechanisms.emulation.EmulationTrialService;
import io.github.checkselfy.bakemechanisms.emulation.PaperEmulationWorld;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry;
import io.github.checkselfy.bakemechanisms.marking.MarkingController;
import io.github.checkselfy.bakemechanisms.marking.PaperOutputResolver;
import io.github.checkselfy.bakemechanisms.profiling.ChunkTickets;
import io.github.checkselfy.bakemechanisms.profiling.PaperProfilingWorld;
import io.github.checkselfy.bakemechanisms.profiling.ProfilingProtection;
import io.github.checkselfy.bakemechanisms.profiling.ProfilingService;
import io.github.checkselfy.bakemechanisms.profiling.ProfilingSettings;
import java.util.Objects;
import org.bukkit.plugin.java.JavaPlugin;

public final class BakeMechanismsPlugin extends JavaPlugin {
    private ProfilingService profiling;
    private EmulationTrialService trials;

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
        EmulationSettings emulationSettings;
        try {
            var defaults = ProfilingSettings.DEFAULT;
            settings = new ProfilingSettings(
                    getConfig().getInt("profiling.snapshot-interval", defaults.snapshotInterval()),
                    getConfig().getLong("profiling.min-useful-ticks", defaults.minUsefulTicks()),
                    getConfig().getBoolean("profiling.reject-short-useful-segment",
                            defaults.rejectShortUsefulSegment()));
            emulationSettings = new EmulationSettings(getConfig().getInt("emulation.update-interval",
                    EmulationSettings.DEFAULT.updateInterval()));
        } catch (IllegalArgumentException error) {
            getLogger().severe("Invalid settings: " + error.getMessage() + " Plugin disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        var registry = new FarmRegistry(maxVolume);
        var resolver = new PaperOutputResolver();
        var paperWorld = new PaperProfilingWorld(this, resolver);
        var tickets = new ChunkTickets(paperWorld);
        profiling = new ProfilingService(registry, paperWorld, settings, tickets);
        trials = new EmulationTrialService(registry, new PaperEmulationWorld(paperWorld), tickets, emulationSettings);
        var marking = new MarkingController(this, registry, resolver, profiling, trials);
        var command = Objects.requireNonNull(getCommand("bake"), "bake command missing in plugin.yml");
        command.setExecutor(marking);
        command.setTabCompleter(marking);
        getServer().getPluginManager().registerEvents(marking, this);
        getServer().getPluginManager().registerEvents(new ProfilingProtection(profiling), this);
        getServer().getScheduler().runTaskTimer(this, () -> {
            profiling.tick();
            trials.tick();
        }, 1L, 1L);
        getLogger().info("Bake Mechanisms enabled");
    }

    @Override
    public void onDisable() {
        // Unfinished profiling never survives a restart: drop protection and chunk tickets.
        if (profiling != null) profiling.cancelAll();
        if (trials != null) trials.stopAll();
        getLogger().info("Bake Mechanisms disabled");
    }
}
