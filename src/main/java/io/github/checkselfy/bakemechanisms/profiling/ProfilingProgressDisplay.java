package io.github.checkselfy.bakemechanisms.profiling;

import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.MarkedFarm;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/** Creator-facing BossBars; profiling snapshots and their cadence are owned by {@link ProfilingService}. */
public final class ProfilingProgressDisplay implements ProfilingService.ProgressListener, Listener {
    private static final long UPDATE_INTERVAL_TICKS = 20;
    private final Server server;
    private final Map<UUID, ActiveBar> bars = new HashMap<>();

    private record ActiveBar(Player player, BossBar bar, int durationTicks) { }

    public ProfilingProgressDisplay(Server server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public void started(MarkedFarm farm) {
        started(farm, ProfilingSettings.DURATION_TICKS);
    }

    @Override
    public void started(MarkedFarm farm, int durationTicks) {
        var player = server.getPlayer(farm.creatorId());
        if (player == null) return;
        var bar = BossBar.bossBar(title(farm.id(), 0, durationTicks), 0.0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        bars.put(farm.id(), new ActiveBar(player, bar, durationTicks));
        player.showBossBar(bar);
    }

    @Override
    public void progressed(MarkedFarm farm, long elapsedTicks) {
        if (elapsedTicks % UPDATE_INTERVAL_TICKS != 0) return;
        var active = bars.get(farm.id());
        if (active == null) return;
        float progress = (float) elapsedTicks / active.durationTicks();
        active.bar().progress(progress);
        active.bar().name(title(farm.id(), elapsedTicks, active.durationTicks()));
    }

    @Override
    public void ended(UUID farmId) {
        remove(farmId);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        var player = event.getPlayer();
        var iterator = bars.entrySet().iterator();
        while (iterator.hasNext()) {
            var active = iterator.next().getValue();
            if (!active.player().getUniqueId().equals(player.getUniqueId())) continue;
            player.hideBossBar(active.bar());
            iterator.remove();
        }
    }

    private void remove(UUID farmId) {
        var active = bars.remove(farmId);
        if (active != null) active.player().hideBossBar(active.bar());
    }

    private static Component title(UUID farmId, long elapsedTicks, int durationTicks) {
        long remainingTicks = durationTicks - elapsedTicks;
        int percent = (int) (elapsedTicks * 100 / durationTicks);
        return Component.text("Ферма " + farmId + " · " + percent + "% · прошло " + time(elapsedTicks)
                + " · осталось " + time(remainingTicks));
    }

    private static String time(long ticks) {
        long seconds = (ticks + 19) / 20;
        return seconds >= 60 ? seconds / 60 + " мин " + seconds % 60 + " с" : seconds + " с";
    }
}
