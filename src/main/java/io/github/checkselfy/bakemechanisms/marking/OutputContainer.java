package io.github.checkselfy.bakemechanisms.marking;

import java.util.Objects;
import java.util.Set;

/** Physical blocks of one inventory, resolved from the world by the Paper adapter. */
public record OutputContainer(Type type, Set<BlockPosition> blocks) {
    public enum Type { CHEST, DOUBLE_CHEST, BARREL }

    public OutputContainer {
        Objects.requireNonNull(type, "type");
        blocks = Set.copyOf(blocks);
        if (blocks.size() != (type == Type.DOUBLE_CHEST ? 2 : 1)) {
            throw new IllegalArgumentException("Неверное число блоков выходного контейнера.");
        }
        if (type == Type.DOUBLE_CHEST) {
            var iterator = blocks.iterator();
            var first = iterator.next();
            var second = iterator.next();
            if (!first.worldId().equals(second.worldId()) || first.y() != second.y()
                    || Math.abs((long) first.x() - second.x())
                    + Math.abs((long) first.z() - second.z()) != 1) {
                throw new IllegalArgumentException("Половины двойного сундука должны быть соседними.");
            }
        }
    }
}
