package io.github.checkselfy.bakemechanisms.profiling;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.checkselfy.bakemechanisms.marking.BlockPosition;
import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry;
import io.github.checkselfy.bakemechanisms.marking.OutputContainer;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProfilingProgressDisplayTest {
    private final UUID creator = UUID.randomUUID();
    private final Server server = mock(Server.class);
    private final Player player = mock(Player.class);
    private final FarmRegistry registry = new FarmRegistry(FarmRegistry.DEFAULT_MAX_VOLUME);
    private final ProfilingProgressDisplay display = new ProfilingProgressDisplay(server);

    @BeforeEach
    void setup() {
        when(server.getPlayer(creator)).thenReturn(player);
        when(player.getUniqueId()).thenReturn(creator);
    }

    @Test
    void showsFarmPercentageElapsedAndRemainingTimeAndUpdatesOncePerSecond() {
        var farm = farm(0);
        display.started(farm);
        var bar = shownBar();

        assertBar(bar, farm, 0, 0.0f, "0 с", "3 мин 0 с");
        for (int tick = 1; tick < 20; tick++) display.progressed(farm, tick);
        assertBar(bar, farm, 0, 0.0f, "0 с", "3 мин 0 с");

        display.progressed(farm, 20);
        assertBar(bar, farm, 0, 20.0f / ProfilingSettings.DURATION_TICKS, "1 с", "2 мин 59 с");
        display.progressed(farm, 1800);
        assertBar(bar, farm, 50, 0.5f, "1 мин 30 с", "1 мин 30 с");
    }

    @Test
    void concurrentFarmsHaveIndependentLabelledBars() {
        var first = farm(0);
        var second = farm(32);
        display.started(first);
        display.started(second);
        var captor = ArgumentCaptor.forClass(BossBar.class);
        verify(player, times(2)).showBossBar(captor.capture());
        var firstBar = captor.getAllValues().get(0);
        var secondBar = captor.getAllValues().get(1);

        display.progressed(first, 1800);
        display.progressed(second, 900);
        assertTrue(text(firstBar).contains(first.id().toString()));
        assertTrue(text(secondBar).contains(second.id().toString()));
        assertEquals(0.5f, firstBar.progress());
        assertEquals(0.25f, secondBar.progress());

        display.ended(first.id());
        verify(player).hideBossBar(firstBar);
        verify(player, never()).hideBossBar(secondBar);
    }

    @Test
    void quitRemovesBarWithoutRecreatingItOnLaterTicks() {
        var farm = farm(0);
        display.started(farm);
        var bar = shownBar();
        var quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(player);

        display.onQuit(quit);
        display.progressed(farm, 20);
        display.ended(farm.id());

        verify(player).hideBossBar(bar);
        verify(player).showBossBar(bar);
    }

    private BossBar shownBar() {
        var captor = ArgumentCaptor.forClass(BossBar.class);
        verify(player).showBossBar(captor.capture());
        return captor.getValue();
    }

    private static void assertBar(BossBar bar, FarmRegistry.MarkedFarm farm, int percent, float progress,
                                  String elapsed, String remaining) {
        assertEquals(progress, bar.progress());
        assertEquals(Component.text("Ферма " + farm.id() + " · " + percent + "% · прошло " + elapsed
                + " · осталось " + remaining), bar.name());
    }

    private static String text(BossBar bar) {
        return ((Component) bar.name()).toString();
    }

    private FarmRegistry.MarkedFarm farm(int x) {
        var world = UUID.randomUUID();
        var mechanism = new Cuboid(new BlockPosition(world, x, 0, 0), new BlockPosition(world, x + 5, 5, 5));
        var output = new OutputContainer(OutputContainer.Type.CHEST,
                Set.of(new BlockPosition(world, x + 20, 0, 0)));
        return registry.register(creator, mechanism, output);
    }
}
