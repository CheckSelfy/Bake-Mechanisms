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
import io.github.checkselfy.bakemechanisms.stasis.FileSnapshotStore;
import io.github.checkselfy.bakemechanisms.stasis.PaperStasisWorld;
import io.github.checkselfy.bakemechanisms.stasis.StasisCommands;
import io.github.checkselfy.bakemechanisms.stasis.StasisProtection;
import io.github.checkselfy.bakemechanisms.stasis.StasisService;
import io.github.checkselfy.bakemechanisms.storage.FarmStorage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.logging.Level;
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
        var storage = new FarmStorage(getDataFolder().toPath().resolve("farms"));
        var registry = new FarmRegistry(maxVolume, farm -> {
            try {
                storage.save(farm);
            } catch (UncheckedIOException error) {
                getLogger().log(Level.SEVERE, "Failed to save farm " + farm.id(), error);
            }
        });
        try {
            for (var stored : storage.loadAll()) {
                var saved = stored.farm();
                var restored = registry.restore(saved);
                if (restored.state() != saved.state()) {
                    getLogger().info("Farm " + saved.id() + ": unfinished profiling cancelled by restart, now "
                            + restored.state() + ".");
                }
            }
        } catch (IllegalStateException | IllegalArgumentException | UncheckedIOException error) {
            // Never start over a partial load: saving would overwrite farms that failed to load.
            getLogger().log(Level.SEVERE, "Failed to load farms; plugin disabled. Fix or move the data and restart.",
                    error);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("Loaded " + registry.all().size() + " farm(s).");
        var resolver = new PaperOutputResolver();
        var paperWorld = new PaperProfilingWorld(this, resolver);
        var tickets = new ChunkTickets(paperWorld);
        profiling = new ProfilingService(registry, paperWorld, settings, tickets);
        trials = new EmulationTrialService(registry, new PaperEmulationWorld(paperWorld), tickets, emulationSettings);
        // Snapshots share the farm directories with FarmStorage; the stasis manifest is authoritative for STASIS.
        var stasis = new StasisService(registry, new PaperStasisWorld(this, resolver),
                new FileSnapshotStore(getDataFolder().toPath().resolve("farms"), getServer().getMinecraftVersion()),
                trials::isRunning);
        try {
            for (var id : stasis.recover()) {
                getLogger().warning("Farm " + id + ": stored as STASIS without an active snapshot, now NEEDS_PROFILING.");
            }
        } catch (IOException | RuntimeException error) {
            getLogger().log(Level.SEVERE, "Cannot recover active snapshots; plugin disabled.", error);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        var marking = new MarkingController(this, registry, resolver, profiling, trials);
        var commands = new StasisCommands(marking, registry, stasis, getLogger(),
                task -> getServer().getScheduler().runTaskLater(this, task, 1L));
        var command = Objects.requireNonNull(getCommand("bake"), "bake command missing in plugin.yml");
        command.setExecutor(commands);
        command.setTabCompleter(commands);
        getServer().getPluginManager().registerEvents(marking, this);
        getServer().getPluginManager().registerEvents(new ProfilingProtection(profiling), this);
        getServer().getPluginManager().registerEvents(new StasisProtection(stasis), this);
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
