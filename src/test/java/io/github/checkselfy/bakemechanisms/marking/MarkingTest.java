package io.github.checkselfy.bakemechanisms.marking;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public final class MarkingTest {
    private static final UUID WORLD = UUID.randomUUID();
    private static int checks;

    @Test
    void registrationRules() {
        var creator = UUID.randomUUID();
        var box = new Cuboid(block(63, 63, 63), block(0, 0, 0));
        check(!box.exceedsVolume(262_144), "64 cubed is allowed");
        check(box.exceedsVolume(262_143), "volume limit is inclusive");
        check(new Cuboid(block(0, 0, 0), block(0, 0, 0)).contains(block(0, 0, 0)), "single block");
        check(box.contains(block(63, 63, 63)), "inclusive upper corner");
        check(!box.contains(block(64, 0, 0)), "outside bounds");
        check(new Cuboid(block(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE),
                block(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE))
                .exceedsVolume(Long.MAX_VALUE), "volume does not overflow");
        reject("одном мире", () -> new Cuboid(block(0, 0, 0),
                new BlockPosition(UUID.randomUUID(), 0, 0, 0)));

        var registry = new FarmRegistry(FarmRegistry.DEFAULT_MAX_VOLUME);
        var farm = registry.register(creator, box, output(block(64, 0, 0)));
        check(farm.state() == FarmRegistry.State.NEEDS_PROFILING, "initial state");
        check(registry.requireCreator(farm.id(), creator).equals(farm), "creator can view registration");
        reject("создатель", () -> registry.requireCreator(farm.id(), UUID.randomUUID()));
        reject("не найдена", () -> registry.requireCreator(UUID.randomUUID(), creator));
        check(registry.findByCreator(UUID.randomUUID()).isEmpty(), "creator list is isolated");
        reject("Чанки", () -> registry.register(creator, box, output(block(100, 0, 0))));
        reject("Чанки", () -> registry.register(creator,
                new Cuboid(block(0, 100, 0), block(0, 101, 0)), output(block(100, 0, 0))));
        reject("предел", () -> registry.register(creator,
                new Cuboid(block(100, 0, 0), block(164, 63, 63)), output(block(200, 0, 0))));
        reject("вне механизма", () -> new FarmRegistry(100).register(creator,
                new Cuboid(block(0, 0, 0), block(0, 0, 0)), output(block(0, 0, 0))));

        var secondBox = new Cuboid(block(96, 0, 0), block(96, 0, 0));
        reject("уже назначен", () -> registry.register(creator, secondBox, output(block(64, 0, 0))));
        reject("внутри другой", () -> registry.register(creator, secondBox, output(block(1, 0, 0))));
        reject("включает выходной", () -> registry.register(creator,
                new Cuboid(block(64, 0, 0), block(64, 0, 0)), output(block(100, 0, 0))));
        reject("одном мире", () -> registry.register(creator, secondBox,
                output(new BlockPosition(UUID.randomUUID(), 200, 0, 0))));
        check(registry.findByCreator(creator).size() == 1, "rejections do not mutate registry");
        registry.register(UUID.randomUUID(), secondBox, output(block(100, 0, 0)));
        check(registry.findByCreator(creator).size() == 1, "multiple creators");

        check(!new Cuboid(block(-1, 0, 0), block(-1, 0, 0)).sharesMechanismChunks(
                new Cuboid(block(0, 0, 0), block(0, 0, 0))), "negative chunk boundary");
        check(new Cuboid(block(-16, 0, 0), block(-16, 0, 0)).sharesMechanismChunks(
                new Cuboid(block(-1, 100, 0), block(-1, 100, 0))), "negative chunk overlap");
        var otherWorldBlock = new BlockPosition(UUID.randomUUID(), 0, 0, 0);
        check(!box.sharesMechanismChunks(new Cuboid(otherWorldBlock, otherWorldBlock)), "world isolation");

        var doubles = new FarmRegistry(100);
        var left = block(31, 0, 0);
        var right = block(32, 0, 0);
        var doubleOutput = new OutputContainer(OutputContainer.Type.DOUBLE_CHEST, Set.of(left, right));
        doubles.register(creator, new Cuboid(block(0, 0, 0), block(0, 0, 0)), doubleOutput);
        reject("уже назначен", () -> doubles.register(creator,
                new Cuboid(block(64, 0, 0), block(64, 0, 0)), output(right)));
        reject("вне механизма", () -> new FarmRegistry(100).register(creator,
                new Cuboid(right, right), doubleOutput));
        reject("число блоков", () -> new OutputContainer(OutputContainer.Type.BARREL, Set.of(left, right)));
        reject("соседними", () -> new OutputContainer(OutputContainer.Type.DOUBLE_CHEST,
                Set.of(left, block(33, 0, 0))));
        var barrelRegistry = new FarmRegistry(100);
        barrelRegistry.register(creator, new Cuboid(block(0, 0, 0), block(0, 0, 0)),
                new OutputContainer(OutputContainer.Type.BARREL, Set.of(left)));
        check(barrelRegistry.findByCreator(creator).size() == 1, "barrel accepted");
        reject("положительным", () -> new FarmRegistry(0));
        System.out.println("Passed " + checks + " marking checks.");
    }

    private static BlockPosition block(int x, int y, int z) {
        return new BlockPosition(WORLD, x, y, z);
    }

    private static OutputContainer output(BlockPosition block) {
        return new OutputContainer(OutputContainer.Type.CHEST, Set.of(block));
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks++;
    }

    private static void reject(String messageFragment, Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException error) {
            check(error.getMessage().contains(messageFragment), "Unexpected rejection: " + error.getMessage());
            return;
        }
        throw new AssertionError("Expected rejection containing: " + messageFragment);
    }
}
