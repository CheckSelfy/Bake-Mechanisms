package io.github.checkselfy.bakemechanisms;

import io.github.checkselfy.bakemechanisms.marking.FarmRegistry;
import io.github.checkselfy.bakemechanisms.marking.MarkingController;
import io.github.checkselfy.bakemechanisms.marking.PaperOutputResolver;
import java.util.Objects;
import org.bukkit.plugin.java.JavaPlugin;

public final class BakeMechanismsPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        saveDefaultConfig();
        long maxVolume = getConfig().getLong("max-mechanism-volume", FarmRegistry.DEFAULT_MAX_VOLUME);
        if (maxVolume < 1) {
            getLogger().severe("max-mechanism-volume must be positive; plugin disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        var registry = new FarmRegistry(maxVolume);
        var marking = new MarkingController(this, registry, new PaperOutputResolver());
        var command = Objects.requireNonNull(getCommand("bake"), "bake command missing in plugin.yml");
        command.setExecutor(marking);
        command.setTabCompleter(marking);
        getServer().getPluginManager().registerEvents(marking, this);
        getLogger().info("Bake Mechanisms enabled");
    }

    @Override
    public void onDisable() {
        getLogger().info("Bake Mechanisms disabled");
    }
}
