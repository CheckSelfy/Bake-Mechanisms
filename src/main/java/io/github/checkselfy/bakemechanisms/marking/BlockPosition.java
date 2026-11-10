package io.github.checkselfy.bakemechanisms.marking;

import java.util.Objects;
import java.util.UUID;

public record BlockPosition(UUID worldId, int x, int y, int z) {
    public BlockPosition {
        Objects.requireNonNull(worldId, "worldId");
    }
}
