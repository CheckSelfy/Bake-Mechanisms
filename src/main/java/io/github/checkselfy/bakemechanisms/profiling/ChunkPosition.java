package io.github.checkselfy.bakemechanisms.profiling;

import io.github.checkselfy.bakemechanisms.marking.BlockPosition;
import java.util.Objects;
import java.util.UUID;

public record ChunkPosition(UUID worldId, int x, int z) {
    public ChunkPosition {
        Objects.requireNonNull(worldId, "worldId");
    }

    public static ChunkPosition of(BlockPosition block) {
        return new ChunkPosition(block.worldId(), block.x() >> 4, block.z() >> 4);
    }
}
