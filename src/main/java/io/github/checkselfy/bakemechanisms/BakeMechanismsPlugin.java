package io.github.checkselfy.bakemechanisms;

import io.github.checkselfy.bakemechanisms.emulation.EmulationSettings;
import io.github.checkselfy.bakemechanisms.emulation.EmulationState;
import io.github.checkselfy.bakemechanisms.emulation.EmulationStore;
import io.github.checkselfy.bakemechanisms.emulation.StasisEmulationService;
import io.github.checkselfy.bakemechanisms.emulation.PaperEmulationWorld;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry;
import io.github.checkselfy.bakemechanisms.marking.FarmRemoval;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.MarkedFarm;
import io.github.checkselfy.bakemechanisms.marking.DialogTextPrompt;
import io.github.checkselfy.bakemechanisms.marking.FarmScreens;
import io.github.checkselfy.bakemechanisms.marking.MarkingController;
import io.github.checkselfy.bakemechanisms.marking.PaperOutputResolver;
import io.github.checkselfy.bakemechanisms.marking.ReadyMechanismWatcher;
import io.github.checkselfy.bakemechanisms.profiling.ChunkTickets;
import io.github.checkselfy.bakemechanisms.profiling.PaperProfilingWorld;
import io.github.checkselfy.bakemechanisms.profiling.ProfilingProtection;
import io.github.checkselfy.bakemechanisms.profiling.ProfilingTransportProtection;
import io.github.checkselfy.bakemechanisms.profiling.ProfilingProgressDisplay;
import io.github.checkselfy.bakemechanisms.profiling.ProfilingService;
import io.github.checkselfy.bakemechanisms.profiling.ProfilingSettings;
import io.github.checkselfy.bakemechanisms.stasis.FileSnapshotStore;
import io.github.checkselfy.bakemechanisms.stasis.OfflinePlayerSafety;
import io.github.checkselfy.bakemechanisms.stasis.PaperStasisWorld;
import io.github.checkselfy.bakemechanisms.stasis.StasisCommands;
import io.github.checkselfy.bakemechanisms.stasis.StasisProduction;
import io.github.checkselfy.bakemechanisms.stasis.StasisProtection;
import io.github.checkselfy.bakemechanisms.stasis.StasisService;
import io.github.checkselfy.bakemechanisms.storage.FarmStorage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Material;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.java.JavaPlugin;

public final class BakeMechanismsPlugin extends JavaPlugin {
    private ProfilingService profiling;
    private StasisEmulationService emulation;
    private OfflinePlayerSafety playerSafety;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        long maxVolume = getConfig().getLong("max-mechanism-volume", FarmRegistry.DEFAULT_MAX_VOLUME);
        if (maxVolume < 1) {
            getLogger().severe("max-mechanism-volume must be positive; plugin disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        int maxOutputs = getConfig().getInt("max-output-containers", FarmRegistry.DEFAULT_MAX_OUTPUTS);
        if (maxOutputs < 1) {
            getLogger().severe("max-output-containers must be positive; plugin disabled.");
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
        var registry = new FarmRegistry(maxVolume, maxOutputs, farm -> {
            try {
                storage.save(farm);
            } catch (UncheckedIOException error) {
                getLogger().log(Level.SEVERE, "Failed to save farm " + farm.id(), error);
            }
        });
        var stored = new HashMap<UUID, FarmStorage.StoredFarm>();
        try {
            for (var farmData : storage.loadAll()) {
                stored.put(farmData.farm().id(), farmData);
                var saved = farmData.farm();
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
        // Collected before recovery, which consumes the saved progress of farms in stasis.
        var drains = stored.values().stream().filter(FarmStorage.StoredFarm::draining).toList();
        emulation = new StasisEmulationService(new PaperEmulationWorld(paperWorld), tickets, emulationSettings,
                loggingStore(storage));
        var progressDisplay = new ProfilingProgressDisplay(getServer());
        profiling = new ProfilingService(registry, paperWorld, settings, tickets, emulation::draining, progressDisplay);
        // Snapshots share the farm directories with FarmStorage; the stasis manifest is authoritative for STASIS.
        playerSafety = new OfflinePlayerSafety(getServer(), getDataFolder().toPath().resolve("offline-players.dat"));
        var stasisWorld = new PaperStasisWorld(this, resolver, playerSafety);
        getServer().getPluginManager().registerEvents(playerSafety, this);
        var stasis = new StasisService(registry, stasisWorld,
                new FileSnapshotStore(getDataFolder().toPath().resolve("farms"), getServer().getMinecraftVersion()),
                production(emulation, stored));
        try {
            for (var id : stasis.recover()) {
                getLogger().warning("Farm " + id + ": stored as STASIS without an active snapshot, now NEEDS_PROFILING.");
            }
        } catch (IOException | RuntimeException error) {
            getLogger().log(Level.SEVERE, "Cannot recover active snapshots; plugin disabled.", error);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // Restored farms deliver what was left of their buffer; this needs no profile.
        for (var farmData : drains) {
            emulation.drain(registry.requireCreator(farmData.farm().id(), farmData.farm().creatorId()),
                    farmData.drain());
        }
        // Snapshot files share the farm directory, and storage refuses to delete one with an active snapshot.
        var removal = new FarmRemoval(registry, storage::delete, emulation::stopDraining, System::currentTimeMillis);
        var command = Objects.requireNonNull(getCommand("bake"), "bake command missing in plugin.yml");
        // Farm screens run /bake subcommands, whose executor wraps the marking controller that opens the screens.
        var executor = new AtomicReference<TabExecutor>();
        Consumer<Runnable> nextTick = task -> getServer().getScheduler().runTask(this, task);
        var prompt = new DialogTextPrompt(nextTick);
        var farmScreens = new FarmScreens(registry,
                (player, args) -> executor.get().onCommand(player, command, command.getName(), args), getServer(),
                prompt, nextTick);
        var marking = new MarkingController(this, registry, resolver, profiling, removal, farmScreens::openList,
                prompt);
        var commands = new StasisCommands(marking, registry, stasis, getLogger(),
                task -> getServer().getScheduler().runTaskLater(this, task, 1L));
        executor.set(commands);
        command.setExecutor(commands);
        command.setTabCompleter(commands);
        getServer().getPluginManager().registerEvents(marking, this);
        getServer().getPluginManager().registerEvents(farmScreens, this);
        getServer().getPluginManager().registerEvents(progressDisplay, this);
        getServer().getPluginManager().registerEvents(new ProfilingProtection(profiling), this);
        getServer().getPluginManager().registerEvents(new ProfilingTransportProtection(profiling), this);
        getServer().getPluginManager().registerEvents(new ReadyMechanismWatcher(registry, getServer()), this);
        getServer().getPluginManager().registerEvents(new StasisProtection(stasis), this);
        getServer().getScheduler().runTaskTimer(this, () -> {
            profiling.tick();
            emulation.tick();
        }, 1L, 1L);
        getServer().getScheduler().runTaskTimer(this, marking::refreshActionBars, 20L, 20L);
        getLogger().info("Bake Mechanisms enabled");
    }

    @Override
    public void onDisable() {
        if (playerSafety != null) playerSafety.shutdown();
        // Unfinished profiling never survives a restart: drop protection and chunk tickets.
        if (profiling != null) profiling.cancelAll();
        // Production of farms in stasis is saved and continues after the restart.
        if (emulation != null) emulation.stopAll();
        getLogger().info("Bake Mechanisms disabled");
    }

    /**
     * Starts production with the progress saved before the restart. Saved progress belongs to the profile it was
     * produced with; any other profile starts from scratch.
     */
    private static StasisProduction production(StasisEmulationService emulation,
                                               Map<UUID, FarmStorage.StoredFarm> stored) {
        return new StasisProduction() {
            @Override public void start(MarkedFarm farm) {
                var saved = stored.remove(farm.id());
                var state = saved != null && saved.emulation().isPresent()
                        && Objects.equals(saved.farm().profile(), farm.profile())
                        ? saved.emulation().get() : EmulationState.EMPTY;
                emulation.start(farm, state);
            }

            @Override public void pause(UUID farmId) { emulation.pause(farmId); }

            @Override public void resume(UUID farmId) { emulation.resume(farmId); }

            @Override public void finish(UUID farmId) { emulation.finish(farmId); }
        };
    }

    /** A failed write is logged; the progress stays in memory and is written again on the next change. */
    private EmulationStore loggingStore(FarmStorage storage) {
        return new EmulationStore() {
            @Override public void saveEmulation(UUID farmId, EmulationState state) {
                attempt(farmId, () -> storage.saveEmulation(farmId, state));
            }

            @Override public void saveDrain(UUID farmId, List<Map<Material, Integer>> buffers) {
                attempt(farmId, () -> storage.saveDrain(farmId, buffers));
            }

            @Override public void deleteDrain(UUID farmId) {
                attempt(farmId, () -> storage.deleteDrain(farmId));
            }

            private void attempt(UUID farmId, Runnable write) {
                try {
                    write.run();
                } catch (UncheckedIOException error) {
                    getLogger().log(Level.SEVERE, "Failed to save emulation of farm " + farmId, error);
                }
            }
        };
    }
}
