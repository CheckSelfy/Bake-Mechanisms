package io.github.checkselfy.bakemechanisms.stasis;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.and;
import static org.mockito.Mockito.*;

import io.github.checkselfy.bakemechanisms.marking.BlockPosition;
import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry;
import io.github.checkselfy.bakemechanisms.marking.OutputContainer;
import io.github.checkselfy.bakemechanisms.profiling.ProductionProfile;
import java.util.Set;
import org.bukkit.Material;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.command.Command;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class StasisCommandsTest {
    private final UUID creator = UUID.randomUUID();
    private final FarmRegistry registry = new FarmRegistry(100);
    private final StasisService service = mock(StasisService.class);
    private final TabExecutor delegate = mock(TabExecutor.class);
    private final StasisCommands commands = new StasisCommands(delegate, registry, service, Logger.getAnonymousLogger());
    private final Command command = mock(Command.class);

    @Test void routesStasisAndUnstasisWithoutInterceptingProfilingCommands() {
        var player = player(creator);
        var id = farm().id();
        commands.onCommand(player, command, "bake", new String[] { "stasis", "--id", id.toString() });
        commands.onCommand(player, command, "bake", new String[] { "unstasis", "--id", id.toString() });
        verify(service).enter(id, creator);
        verify(service).restore(id, creator);
        var profile = new String[] { "profile", id.toString() };
        commands.onCommand(player, command, "bake", profile);
        verify(delegate).onCommand(player, command, "bake", profile);
    }

    @Test void oldRestoreNameIsLeftToTheOtherCommandsAndNotCompleted() {
        var player = player(creator);
        var restore = new String[] { "restore", "--id", farm().id().toString() };
        commands.onCommand(player, command, "bake", restore);
        verify(delegate).onCommand(player, command, "bake", restore);
        verifyNoInteractions(service);
        assertEquals(java.util.List.of("unstasis"),
                commands.onTabComplete(player, command, "bake", new String[] { "uns" }));
        assertTrue(commands.onTabComplete(player, command, "bake", new String[] { "res" }).isEmpty());
    }

    @Test void onlyCreatorsFarmIdsAppearInCompletions() {
        var world = UUID.randomUUID();
        var point = new BlockPosition(world, 0, 64, 0);
        var farm = registry.register(creator, new Cuboid(point, point), new OutputContainer(OutputContainer.Type.BARREL,
                Set.of(new BlockPosition(world, 1, 64, 0))));
        registry.markProfiling(farm.id());
        registry.markReady(farm.id(), new ProductionProfile(java.util.List.of(java.util.Map.of(Material.CACTUS, 1.0)), 3600));
        registry.markStasis(farm.id());
        assertEquals(java.util.List.of("--id"), commands.onTabComplete(player(creator), command,
                "bake", new String[] { "unstasis", "" }), "an unnamed farm is reached through --id");
        assertEquals(java.util.List.of(farm.id().toString()), commands.onTabComplete(player(creator), command,
                "bake", new String[] { "unstasis", "--id", "" }));
        assertTrue(commands.onTabComplete(player(UUID.randomUUID()), command, "bake",
                new String[] { "unstasis", "--id", "" }).isEmpty());
    }

    @Test void farmsAreCompletedOnlyForTheOperationTheirStateAllows() {
        var world = UUID.randomUUID();
        var ready = registry.register(creator, "Ready", new Cuboid(new BlockPosition(world, 0, 64, 0),
                new BlockPosition(world, 0, 64, 0)), java.util.List.of(new OutputContainer(OutputContainer.Type.BARREL,
                Set.of(new BlockPosition(world, 1, 64, 0)))));
        registry.markProfiling(ready.id());
        registry.markReady(ready.id(), new ProductionProfile(java.util.List.of(java.util.Map.of(Material.CACTUS, 1.0)), 3600));
        registry.register(creator, "Needs", new Cuboid(new BlockPosition(world, 64, 64, 0),
                new BlockPosition(world, 64, 64, 0)), java.util.List.of(new OutputContainer(OutputContainer.Type.BARREL,
                Set.of(new BlockPosition(world, 65, 64, 0)))));
        assertEquals(java.util.List.of("Ready", "--id"), commands.onTabComplete(player(creator), command, "bake",
                new String[] { "stasis", "" }));
        assertEquals(java.util.List.of(ready.id().toString()), commands.onTabComplete(player(creator), command, "bake",
                new String[] { "stasis", "--id", "" }));
        assertEquals(java.util.List.of("--id"), commands.onTabComplete(player(creator), command, "bake",
                new String[] { "unstasis", "" }));
    }

    @Test void badUuidAndInvalidStateProduceReadableMessages() {
        var player = player(creator);
        commands.onCommand(player, command, "bake", new String[] { "stasis", "--id", "bad" });
        verify(player).sendMessage("Укажите UUID фермы из /bake list.");
        verifyNoInteractions(service);
        var id = farm().id();
        doThrow(new IllegalArgumentException("Стазис доступен только из READY.")).when(service).enter(id, creator);
        commands.onCommand(player, command, "bake", new String[] { "stasis", "--id", id.toString() });
        verify(player).sendMessage("Стазис доступен только из READY.");
    }

    private Player player(UUID id) {
        var player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    @Test void movingPistonWaitsOneTickAndDuplicateRequestDoesNotStartAnotherRetry() {
        var player = player(creator);
        var id = farm().id();
        var tasks = new java.util.ArrayDeque<Runnable>();
        var deferred = new StasisCommands(delegate, registry, service, Logger.getAnonymousLogger(), tasks::add);
        doThrow(new PistonInMotionException()).doNothing().when(service).enter(id, creator);
        var args = new String[] { "stasis", "--id", id.toString() };
        deferred.onCommand(player, command, "bake", args);
        assertEquals(1, tasks.size());
        deferred.onCommand(player, command, "bake", args);
        verify(service, times(1)).enter(id, creator);
        tasks.removeFirst().run();
        verify(service, times(2)).enter(id, creator);
        assertTrue(tasks.isEmpty());
        verify(player).sendMessage(contains("STASIS"));
    }

    @Test void continuouslyMovingPistonsHaveBoundedRetriesAndLeaveNoStuckRequest() {
        var player = player(creator);
        var id = farm().id();
        var tasks = new java.util.ArrayDeque<Runnable>();
        var deferred = new StasisCommands(delegate, registry, service, Logger.getAnonymousLogger(), tasks::add);
        doThrow(new PistonInMotionException()).when(service).enter(id, creator);
        var args = new String[] { "stasis", "--id", id.toString() };
        deferred.onCommand(player, command, "bake", args);
        int retries = 0;
        while (!tasks.isEmpty()) {
            checkRetryLimit(++retries);
            tasks.removeFirst().run();
        }
        assertEquals(StasisStabilizer.MAX_WAIT_TICKS, retries);
        verify(service, times(StasisStabilizer.MAX_WAIT_TICKS + 1)).enter(id, creator);
        verify(player).sendMessage(and(startsWith(new PistonInMotionException().getMessage()), contains("READY")));
        doNothing().when(service).enter(id, creator);
        deferred.onCommand(player, command, "bake", args);
        verify(service, times(StasisStabilizer.MAX_WAIT_TICKS + 2)).enter(id, creator);
        assertTrue(tasks.isEmpty());
    }

    @Test void dyingJockeyRetriesAutomaticallyAndReportsProgressToThePlayer() {
        var player = player(creator);
        var id = farm().id();
        var tasks = new java.util.ArrayDeque<Runnable>();
        var deferred = new StasisCommands(delegate, registry, service, Logger.getAnonymousLogger(), tasks::add);
        var dying = new EntityInTransitionException("Сущность ZOMBIFIED_PIGLIN погибает или удаляется.");
        var stubbing = doThrow(dying);
        for (int i = 0; i < 24; i++) stubbing = stubbing.doThrow(dying);
        stubbing.doNothing().when(service).enter(id, creator);
        deferred.onCommand(player, command, "bake", new String[] { "stasis", "--id", id.toString() });
        while (!tasks.isEmpty()) tasks.removeFirst().run();
        verify(service, times(26)).enter(id, creator);
        var order = inOrder(player);
        order.verify(player).sendMessage(and(startsWith("Механизм стабилизируется"), contains("погибает")));
        order.verify(player).sendMessage(and(contains("20/" + StasisStabilizer.MAX_WAIT_TICKS), contains("погибает")));
        order.verify(player).sendMessage(contains("STASIS"));
        verify(player, times(3)).sendMessage(anyString());
    }

    private static void checkRetryLimit(int retries) {
        assertTrue(retries <= StasisStabilizer.MAX_WAIT_TICKS, "unbounded retry loop");
    }

    private FarmRegistry.MarkedFarm farm() {
        var world = UUID.randomUUID();
        var point = new BlockPosition(world, 0, 64, 0);
        return registry.register(creator, new Cuboid(point, point), new OutputContainer(OutputContainer.Type.BARREL,
                Set.of(new BlockPosition(world, 1, 64, 0))));
    }
}
