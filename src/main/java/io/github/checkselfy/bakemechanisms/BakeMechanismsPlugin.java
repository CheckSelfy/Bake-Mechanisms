package io.github.checkselfy.bakemechanisms;

import org.bukkit.plugin.java.JavaPlugin;

public final class BakeMechanismsPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        getLogger().info("Bake Mechanisms enabled");
    }

    @Override
    public void onDisable() {
        getLogger().info("Bake Mechanisms disabled");
    }
}
