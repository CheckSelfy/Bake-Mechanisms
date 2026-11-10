package io.github.checkselfy.bakemechanisms.marking;

import java.util.Objects;

/** Inclusive block bounds. Corner order does not matter. */
public record Cuboid(BlockPosition min, BlockPosition max) {
    public Cuboid {
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        if (!min.worldId().equals(max.worldId())) {
            throw new IllegalArgumentException("Углы должны находиться в одном мире.");
        }
        var first = min;
        var second = max;
        min = new BlockPosition(first.worldId(), Math.min(first.x(), second.x()),
                Math.min(first.y(), second.y()), Math.min(first.z(), second.z()));
        max = new BlockPosition(first.worldId(), Math.max(first.x(), second.x()),
                Math.max(first.y(), second.y()), Math.max(first.z(), second.z()));
    }

    /** Division avoids overflow even for bounds spanning the entire int range. */
    public boolean exceedsVolume(long limit) {
        if (limit < 1) throw new IllegalArgumentException("Предел объёма должен быть положительным.");
        long width = (long) max.x() - min.x() + 1;
        long height = (long) max.y() - min.y() + 1;
        long depth = (long) max.z() - min.z() + 1;
        return width > limit || height > limit / width || depth > limit / width / height;
    }

    public boolean contains(BlockPosition block) {
        return min.worldId().equals(block.worldId())
                && block.x() >= min.x() && block.x() <= max.x()
                && block.y() >= min.y() && block.y() <= max.y()
                && block.z() >= min.z() && block.z() <= max.z();
    }

    public boolean sharesMechanismChunks(Cuboid other) {
        return min.worldId().equals(other.min.worldId())
                && (min.x() >> 4) <= (other.max.x() >> 4)
                && (max.x() >> 4) >= (other.min.x() >> 4)
                && (min.z() >> 4) <= (other.max.z() >> 4)
                && (max.z() >> 4) >= (other.min.z() >> 4);
    }
}
