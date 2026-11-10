package io.github.checkselfy.bakemechanisms.profiling;

import java.util.Objects;
import java.util.UUID;

public record ChunkPosition(UUID worldId, int x, int z) {
    public ChunkPosition {
        Objects.requireNonNull(worldId, "worldId");
    }
}
