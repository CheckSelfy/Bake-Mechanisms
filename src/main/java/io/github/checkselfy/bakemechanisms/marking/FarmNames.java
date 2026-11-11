package io.github.checkselfy.bakemechanisms.marking;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Rules for names that creators give their farms. A name is one command argument and is case-sensitive:
 * {@code Iron} and {@code iron} are different names.
 */
public final class FarmNames {
    public static final int MAX_LENGTH = 32;
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{1," + MAX_LENGTH + "}");

    private FarmNames() { }

    /** @return {@code name} when it is valid */
    public static String requireValid(String name) {
        Objects.requireNonNull(name, "name");
        if (!VALID.matcher(name).matches()) {
            throw new IllegalArgumentException("Имя фермы: от 1 до " + MAX_LENGTH
                    + " символов из латинских букв, цифр, _ и -.");
        }
        if (name.startsWith("--")) throw new IllegalArgumentException("Имя фермы не может начинаться с --.");
        return name;
    }
}
