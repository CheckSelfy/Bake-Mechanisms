package io.github.checkselfy.bakemechanisms.marking;

import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.State;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which farm states each {@code /bake} operation on an existing farm accepts, for completion and the farm screen.
 * The services enforce these rules themselves; this mirror only keeps players from being offered an operation that
 * would be refused.
 */
public final class FarmOperations {
    private static final Map<String, Set<State>> STATES = Map.of(
            "profile", Set.of(State.NEEDS_PROFILING),
            "profile-long", Set.of(State.NEEDS_PROFILING),
            "sprint", Set.of(State.PROFILING),
            "cancel-profiling", Set.of(State.PROFILING),
            "stasis", Set.of(State.READY),
            "unstasis", Set.of(State.STASIS),
            "unregister", Set.of(State.NEEDS_PROFILING, State.READY));

    private FarmOperations() { }

    /** Whether the operation can run on a farm in this state; operations without a rule, like info, accept any. */
    public static boolean accepts(String operation, State state) {
        var states = STATES.get(operation.toLowerCase(Locale.ROOT));
        return states == null || states.contains(state);
    }

    /** The farms the operation can run on. */
    public static List<FarmRegistry.MarkedFarm> eligible(String operation, List<FarmRegistry.MarkedFarm> farms) {
        return farms.stream().filter(farm -> accepts(operation, farm.state())).toList();
    }
}
