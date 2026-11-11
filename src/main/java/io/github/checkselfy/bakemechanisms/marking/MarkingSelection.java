package io.github.checkselfy.bakemechanisms.marking;

import java.util.ArrayList;
import java.util.List;

/**
 * A player's unfinished marking: two corners, chosen output containers and the wand mode that says what the next
 * left click on a block selects. Immutable.
 */
record MarkingSelection(BlockPosition first, BlockPosition second, List<SelectedOutput> outputs, Mode mode) {
    /** What a left click with the wand on a block selects. */
    enum Mode {
        FIRST_CORNER("первый угол"), SECOND_CORNER("второй угол"), OUTPUT("выходной контейнер");

        private final String label;

        Mode(String label) { this.label = label; }

        String label() { return label; }
    }

    /** An output container chosen with the wand, with the block that was clicked to choose it. */
    record SelectedOutput(BlockPosition anchor, OutputContainer output) { }

    private static final String REGISTER_HINT = "ПКМ → «Зарегистрировать ферму»";

    MarkingSelection {
        outputs = List.copyOf(outputs);
    }

    static MarkingSelection empty() {
        return new MarkingSelection(null, null, List.of(), Mode.FIRST_CORNER);
    }

    MarkingSelection withMode(Mode mode) {
        return new MarkingSelection(first, second, outputs, mode);
    }

    MarkingSelection withCorner(Mode corner, BlockPosition block) {
        return new MarkingSelection(corner == Mode.FIRST_CORNER ? block : first,
                corner == Mode.SECOND_CORNER ? block : second, outputs, mode);
    }

    MarkingSelection withOutputs(List<SelectedOutput> outputs) {
        return new MarkingSelection(first, second, outputs, mode);
    }

    /** The first part still missing, or the current mode when the selection is complete. */
    MarkingSelection advanced() {
        if (first == null) return withMode(Mode.FIRST_CORNER);
        if (second == null) return withMode(Mode.SECOND_CORNER);
        if (outputs.isEmpty()) return withMode(Mode.OUTPUT);
        return this;
    }

    boolean complete() {
        return first != null && second != null && !outputs.isEmpty();
    }

    /** Names of the missing parts, in the genitive case, for "не хватает: …". */
    List<String> missing() {
        var missing = new ArrayList<String>();
        if (first == null) missing.add("первого угла");
        if (second == null) missing.add("второго угла");
        if (outputs.isEmpty()) missing.add("выходного контейнера");
        return missing;
    }

    /** The next expected action. */
    String nextStep() {
        return complete() ? REGISTER_HINT : "не хватает: " + String.join(", ", missing());
    }

    /** What to do next, after a step succeeded. */
    String hint() {
        return complete() ? REGISTER_HINT : "ЛКМ по блоку: " + mode.label();
    }

    /** One action-bar line: current mode, chosen data and the next step. */
    String status() {
        return "ЛКМ по блоку: " + mode.label() + " | 1: " + coordinates(first) + " | 2: " + coordinates(second)
                +  " | выходных контейнеров: " + outputs.size() + " | " + nextStep();
    }

    static String coordinates(BlockPosition block) {
        return block == null ? "—" : block.x() + ", " + block.y() + ", " + block.z();
    }
}
