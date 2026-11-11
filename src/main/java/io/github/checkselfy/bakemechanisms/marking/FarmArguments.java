package io.github.checkselfy.bakemechanisms.marking;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Command arguments that name a farm: its name by default, or {@code --id <UUID>}.
 *
 * @param farm the referenced farm
 * @param rest arguments after the farm reference
 */
public record FarmArguments(Reference farm, List<String> rest) {
    public static final String ID_FLAG = "--id";
    /** Usage fragment for command help. */
    public static final String USAGE = "<имя> | " + ID_FLAG + " <UUID>";

    public sealed interface Reference {
        record ById(UUID id) implements Reference {
            public ById { Objects.requireNonNull(id, "id"); }
        }

        record ByName(String name) implements Reference {
            public ByName { Objects.requireNonNull(name, "name"); }
        }
    }

    public FarmArguments {
        Objects.requireNonNull(farm, "farm");
        rest = List.copyOf(rest);
    }

    /**
     * Reads a farm reference starting at {@code args[from]}.
     *
     * @throws IllegalArgumentException when the reference is missing or the UUID is malformed
     */
    public static FarmArguments parse(String[] args, int from) {
        if (args.length <= from) throw new IllegalArgumentException("Укажите ферму: " + USAGE + ".");
        if (!args[from].equalsIgnoreCase(ID_FLAG)) {
            return new FarmArguments(new Reference.ByName(args[from]), rest(args, from + 1));
        }
        if (args.length <= from + 1) throw new IllegalArgumentException("Укажите UUID фермы из /bake list.");
        try {
            return new FarmArguments(new Reference.ById(UUID.fromString(args[from + 1])), rest(args, from + 2));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Укажите UUID фермы из /bake list.");
        }
    }

    /**
     * Tab completions when the last argument is part of a farm reference starting at {@code args[from]}: the
     * names of the player's farms and the {@code --id} flag, then UUIDs after the flag. Names are matched
     * case-sensitively, like the reference itself.
     *
     * @return null when the last argument comes after the farm reference
     */
    public static List<String> complete(List<FarmRegistry.MarkedFarm> farms, String[] args, int from) {
        int last = args.length - 1;
        if (last == from) {
            var prefix = args[last];
            var names = new ArrayList<String>();
            farms.stream().map(FarmRegistry.MarkedFarm::name).filter(Objects::nonNull)
                    .filter(name -> name.startsWith(prefix)).sorted().forEach(names::add);
            if (ID_FLAG.startsWith(prefix.toLowerCase(Locale.ROOT))) names.add(ID_FLAG);
            return List.copyOf(names);
        }
        if (last == from + 1 && args[from].equalsIgnoreCase(ID_FLAG)) {
            var prefix = args[last].toLowerCase(Locale.ROOT);
            return farms.stream().map(farm -> farm.id().toString()).filter(id -> id.startsWith(prefix)).toList();
        }
        return null;
    }

    private static List<String> rest(String[] args, int from) {
        return Arrays.asList(args).subList(Math.min(from, args.length), args.length);
    }
}
