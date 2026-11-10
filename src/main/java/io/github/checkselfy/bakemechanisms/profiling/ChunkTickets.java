package io.github.checkselfy.bakemechanisms.profiling;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Reference-counted plugin chunk tickets shared by every feature that force-loads chunks.
 * Paper keeps one ticket per chunk and plugin, so the last holder alone removes it. Server thread only.
 */
public final class ChunkTickets {
    public interface Backend {
        void addChunkTicket(ChunkPosition chunk);

        void removeChunkTicket(ChunkPosition chunk);
    }

    private final Backend backend;
    private final Map<ChunkPosition, Integer> holders = new HashMap<>();

    public ChunkTickets(Backend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    public void acquire(ChunkPosition chunk) {
        if (!holders.containsKey(chunk)) backend.addChunkTicket(chunk);
        holders.merge(chunk, 1, Integer::sum);
    }

    /** All or nothing: if any ticket fails, those already taken are released and the error rethrown. */
    public void acquireAll(Collection<ChunkPosition> chunks) {
        var acquired = new ArrayList<ChunkPosition>();
        try {
            for (var chunk : chunks) {
                acquire(chunk);
                acquired.add(chunk);
            }
        } catch (RuntimeException error) {
            acquired.forEach(this::release);
            throw error;
        }
    }

    public void release(ChunkPosition chunk) {
        var count = holders.get(chunk);
        if (count == null) throw new IllegalStateException("Ticket not held: " + chunk);
        if (count > 1) {
            holders.put(chunk, count - 1);
        } else {
            holders.remove(chunk);
            backend.removeChunkTicket(chunk);
        }
    }
}
