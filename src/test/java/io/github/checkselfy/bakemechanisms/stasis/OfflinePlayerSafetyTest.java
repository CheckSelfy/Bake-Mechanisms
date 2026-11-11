package io.github.checkselfy.bakemechanisms.stasis;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.checkselfy.bakemechanisms.marking.BlockPosition;
import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.marking.PaperOutputResolver;
import java.util.List;
import java.util.UUID;
import java.nio.file.Path;
import java.nio.file.Files;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfflinePlayerSafetyTest {
    @TempDir Path directory;

    private final Server server = mock(Server.class);
    private final World world = mock(World.class);
    private final UUID worldId = UUID.randomUUID();
    private final UUID playerId = UUID.randomUUID();
    private final OfflinePlayer offline = mock(OfflinePlayer.class);

    private Cuboid region() {
        when(world.getUID()).thenReturn(worldId);
        when(offline.getUniqueId()).thenReturn(playerId);
        when(offline.getName()).thenReturn("LoggedOutPlayer");
        when(server.getOfflinePlayers()).thenReturn(new OfflinePlayer[] { offline });
        var block = new BlockPosition(worldId, 0, 64, 0);
        return new Cuboid(block, block);
    }

    private Player player(double x) {
        var player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, x, 64, 0.5));
        when(player.getBoundingBox()).thenReturn(new BoundingBox(x - 0.3, 64, 0.2, x + 0.3, 65.8, 0.8));
        return player;
    }

    @Test void playerWhoLoggedOutInsideStillOccupiesTheMechanism() {
        var server = mock(Server.class);
        var plugin = mock(Plugin.class);
        var world = mock(World.class);
        var worldId = UUID.randomUUID();
        when(plugin.getServer()).thenReturn(server);
        when(server.getWorld(worldId)).thenReturn(world);
        when(world.getUID()).thenReturn(worldId);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.getPlayers()).thenReturn(List.of());
        var offline = mock(OfflinePlayer.class);
        when(offline.getUniqueId()).thenReturn(UUID.randomUUID());
        when(offline.getName()).thenReturn("LoggedOutPlayer");
        when(offline.getLocation()).thenReturn(new Location(world, 0.5, 64, 0.5));
        when(server.getOfflinePlayers()).thenReturn(new OfflinePlayer[] { offline });
        var block = new BlockPosition(worldId, 0, 64, 0);
        var adapter = new PaperStasisWorld(plugin, new PaperOutputResolver());
        assertTrue(adapter.hasPlayerInside(new Cuboid(block, block)),
                "An offline player inside the cuboid must prevent replacement by bedrock");
    }

    @Test void quitBoundsSurviveNewServiceAndPreserveBoundaryOverlap() {
        var region = region();
        var file = directory.resolve("offline-players.dat");
        var safety = new OfflinePlayerSafety(server, file, player -> 1);
        var player = player(1.2); // Feet outside, body overlaps the block.
        safety.onQuit(quit(player));
        verify(player).saveData();
        assertTrue(safety.hasPlayerInside(region));
        assertTrue(new OfflinePlayerSafety(server, file).hasPlayerInside(region));
    }

    @Test void rejoiningAndLeavingOutsideRemovesTheOldPosition() {
        var region = region();
        var file = directory.resolve("offline-players.dat");
        var safety = new OfflinePlayerSafety(server, file, player -> 1);
        var inside = player(0.5);
        safety.onQuit(quit(inside));
        assertTrue(safety.hasPlayerInside(region));
        when(offline.isOnline()).thenReturn(true);
        safety.onJoin(new PlayerJoinEvent(inside, (net.kyori.adventure.text.Component) null));
        assertFalse(safety.hasPlayerInside(region));
        var outside = player(10);
        safety.onQuit(quit(outside));
        when(offline.isOnline()).thenReturn(false);
        assertFalse(new OfflinePlayerSafety(server, file).hasPlayerInside(region));
    }

    @Test void cleanShutdownSavesPlayersStillOnline() {
        var region = region();
        var file = directory.resolve("offline-players.dat");
        doReturn(List.of(player(0.5))).when(server).getOnlinePlayers();
        new OfflinePlayerSafety(server, file, player -> 1).shutdown();
        assertTrue(new OfflinePlayerSafety(server, file).hasPlayerInside(region));
    }

    @Test void missingAndCorruptDataFailClosed() throws Exception {
        var region = region();
        assertThrows(IllegalArgumentException.class,
                () -> new OfflinePlayerSafety(server).hasPlayerInside(region));
        var file = directory.resolve("offline-players.dat");
        Files.writeString(file, "broken");
        when(offline.getLocation()).thenReturn(new Location(world, 10, 64, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> new OfflinePlayerSafety(server, file).hasPlayerInside(region));
    }

    @Test void otherWorldAndDistantPlayerDoNotBlock() {
        var region = region();
        var safety = new OfflinePlayerSafety(server);
        when(offline.getLocation()).thenReturn(new Location(world, 10, 64, 0.5));
        assertFalse(safety.hasPlayerInside(region));
        var otherWorld = mock(World.class);
        when(otherWorld.getUID()).thenReturn(UUID.randomUUID());
        when(offline.getLocation()).thenReturn(new Location(otherWorld, 0.5, 64, 0.5));
        assertFalse(safety.hasPlayerInside(region));
    }

    @Test void standingScaledBodyIsReservedAfterRestartEvenIfQuitWhileSwimming() {
        var block = region();
        var highBlock = new BlockPosition(worldId, 0, 67, 0);
        var region = new Cuboid(highBlock, highBlock);
        var player = player(0.5);
        when(player.getBoundingBox()).thenReturn(new BoundingBox(0.2, 64, 0.2, 0.8, 64.6, 0.8));
        var file = directory.resolve("offline-players.dat");
        new OfflinePlayerSafety(server, file, ignored -> 2).onQuit(quit(player));
        assertTrue(new OfflinePlayerSafety(server, file).hasPlayerInside(region));
        assertTrue(new OfflinePlayerSafety(server, file).hasPlayerInside(block));
    }

    @Test void writeFailureBlocksOperationsInsteadOfSilentlyLosingProtection() throws Exception {
        var region = region();
        var parent = directory.resolve("not-a-directory");
        Files.writeString(parent, "file");
        when(server.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        var safety = new OfflinePlayerSafety(server, parent.resolve("offline-players.dat"), player -> 1);
        safety.onQuit(quit(player(0.5)));
        assertThrows(IllegalArgumentException.class, () -> safety.hasPlayerInside(region));
    }

    private static PlayerQuitEvent quit(Player player) {
        var event = mock(PlayerQuitEvent.class);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }
}
