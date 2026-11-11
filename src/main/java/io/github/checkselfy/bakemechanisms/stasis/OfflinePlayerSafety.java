package io.github.checkselfy.bakemechanisms.stasis;

import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.profiling.PaperProfilingWorld;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToDoubleFunction;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.BoundingBox;

/** Persists quit bounds; vanilla player data covers players who left before installation. */
public final class OfflinePlayerSafety implements Listener {
    private final Server server;
    private final Map<UUID, Position> departed = new HashMap<>();
    private final Path file;
    private final ToDoubleFunction<Player> scale;
    private String storageFailure;

    public OfflinePlayerSafety(Server server) {
        this(server, null);
    }

    public OfflinePlayerSafety(Server server, Path file) {
        this(server, file, player -> player.getAttribute(org.bukkit.attribute.Attribute.SCALE).getValue());
    }

    OfflinePlayerSafety(Server server, Path file, ToDoubleFunction<Player> scale) {
        this.server = server;
        this.file = file;
        this.scale = scale;
        if (file == null || !Files.exists(file)) return;
        try (var in = new DataInputStream(Files.newInputStream(file))) {
            if (in.readInt() != 1) throw new IOException("Unknown player position format");
            int count = in.readInt();
            if (count < 0 || count > 1_000_000) throw new IOException("Invalid player count");
            for (int i = 0; i < count; i++) {
                var id = UUID.fromString(in.readUTF());
                var world = UUID.fromString(in.readUTF());
                var bounds = new BoundingBox(in.readDouble(), in.readDouble(), in.readDouble(),
                        in.readDouble(), in.readDouble(), in.readDouble());
                departed.put(id, new Position(world, bounds));
            }
            if (in.read() != -1) throw new IOException("Unexpected trailing player data");
        } catch (IOException | IllegalArgumentException error) {
            storageFailure = error.toString();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        var player = event.getPlayer();
        remember(player);
        save();
        // Paper also saves on disconnect. Save explicitly so a subsequent command/restart can read it immediately.
        player.saveData();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        departed.remove(event.getPlayer().getUniqueId());
    }

    public boolean hasPlayerInside(Cuboid mechanism) {
        if (storageFailure != null) throw new IllegalArgumentException(
                "Проверка отключившихся игроков недоступна: " + storageFailure
                        + ". Исправьте файл offline-players.dat и перезапустите сервер.");
        var region = PaperProfilingWorld.box(mechanism);
        for (var player : server.getOfflinePlayers()) {
            if (player.isOnline()) continue;
            var saved = departed.get(player.getUniqueId());
            if (saved == null) saved = savedPosition(player.getLocation());
            if (saved == null) {
                throw new IllegalArgumentException("Нельзя проверить позицию отключившегося игрока "
                        + player.getName() + ". Пусть игрок войдёт на сервер и выйдет из кубоида; повторите команду.");
            }
            if (saved.world().equals(mechanism.min().worldId()) && saved.bounds().overlaps(region)) return true;
        }
        return false;
    }

    private static Position savedPosition(Location location) {
        if (location == null || location.getWorld() == null) return null;
        // Vanilla player data survives a clean restart. Reserve the standing body, including boundary overlap.
        double x = location.getX(), y = location.getY(), z = location.getZ();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return null;
        return new Position(location.getWorld().getUID(),
                new BoundingBox(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3));
    }

    private record Position(UUID world, BoundingBox bounds) {}

    /** Also captures players still online when the plugin is disabled during a clean shutdown. */
    public void shutdown() {
        for (var player : server.getOnlinePlayers()) remember(player);
        save();
    }

    private void remember(Player player) {
        var bounds = player.getBoundingBox().clone();
        var location = player.getLocation();
        // Standing on the next login must be safe even when the player disconnected while crouching/swimming.
        double factor = scale.applyAsDouble(player);
        bounds.union(new BoundingBox(location.getX() - 0.3 * factor, location.getY(), location.getZ() - 0.3 * factor,
                location.getX() + 0.3 * factor, location.getY() + 1.8 * factor, location.getZ() + 0.3 * factor));
        departed.put(player.getUniqueId(), new Position(player.getWorld().getUID(), bounds));
    }

    private void save() {
        if (file == null || storageFailure != null) return;
        var temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (var out = new DataOutputStream(Files.newOutputStream(temporary))) {
                out.writeInt(1);
                out.writeInt(departed.size());
                for (var entry : departed.entrySet()) {
                    out.writeUTF(entry.getKey().toString());
                    out.writeUTF(entry.getValue().world().toString());
                    var b = entry.getValue().bounds();
                    out.writeDouble(b.getMinX()); out.writeDouble(b.getMinY()); out.writeDouble(b.getMinZ());
                    out.writeDouble(b.getMaxX()); out.writeDouble(b.getMaxY()); out.writeDouble(b.getMaxZ());
                }
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            storageFailure = error.toString();
            server.getLogger().severe("Cannot save offline player positions: " + error);
        }
    }
}
