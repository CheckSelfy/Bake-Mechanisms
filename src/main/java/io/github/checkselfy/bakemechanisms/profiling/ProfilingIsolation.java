package io.github.checkselfy.bakemechanisms.profiling;

import io.github.checkselfy.bakemechanisms.marking.BlockPosition;
import io.github.checkselfy.bakemechanisms.marking.Cuboid;
import io.github.checkselfy.bakemechanisms.marking.FarmRegistry.MarkedFarm;
import io.github.checkselfy.bakemechanisms.marking.OutputContainer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;

/** Session-owned transport policy: the mechanism produces; outputs are sinks; the surrounding belt only drains. */
public final class ProfilingIsolation {
    public record Sources(Set<UUID> actors, Set<Material> equipment) {
        public Sources { actors = Set.copyOf(actors); equipment = Set.copyOf(equipment); }
        public static Sources empty() { return new Sources(Set.of(), Set.of()); }
    }
    public static final int MARGIN = 2;
    private final MarkedFarm farm;
    private final Cuboid area;
    private final Set<UUID> trustedItems = new HashSet<>();
    private final Set<UUID> playerItems = new HashSet<>();
    private final Set<UUID> actors = new HashSet<>();
    private final Set<Material> initialEquipment = new HashSet<>();

    public ProfilingIsolation(MarkedFarm farm) { this.farm = farm; this.area = farm.mechanism().expanded(MARGIN); }
    public MarkedFarm farm() { return farm; }
    public Cuboid area() { return area; }
    public boolean isOutput(BlockPosition position) { return farm.outputBlocks().anyMatch(position::equals); }
    public boolean trusted(UUID item) { return trustedItems.contains(item) && !playerItems.contains(item); }
    public void produced(UUID item) { if (!playerItems.contains(item)) trustedItems.add(item); }
    public void playerDropped(UUID item) { playerItems.add(item); trustedItems.remove(item); }
    public void removed(UUID item) { trustedItems.remove(item); playerItems.remove(item); }
    public void actor(UUID id) { actors.add(id); }
    public boolean hasActor(UUID id) { return actors.contains(id); }
    public void initialize(Sources sources) { actors.addAll(sources.actors()); initialEquipment.addAll(sources.equipment()); }
    public boolean ambiguous(Material type) { return type == Material.SHEARS || initialEquipment.contains(type); }

    public static void requireAdjacentOutputs(Cuboid mechanism, List<OutputContainer> outputs) {
        var adjacent = mechanism.expanded(1);
        if (outputs.stream().flatMap(output -> output.blocks().stream()).anyMatch(block -> !adjacent.contains(block))) {
            throw new IllegalArgumentException("Каждый блок выходного контейнера должен быть не дальше одного блока "
                    + "от кубоида механизма. Перенесите выход к границе; подающие воронки включите в механизм.");
        }
    }

    public boolean touches(Set<BlockPosition> inventory) { return inventory.stream().anyMatch(area::contains); }

    public boolean transferAllowed(Set<BlockPosition> source, Set<BlockPosition> destination) {
        boolean fromHere = touches(source), toHere = touches(destination);
        if (!fromHere && !toHere) return true;
        if (source.isEmpty() || destination.isEmpty()) return false;
        if (toHere && source.stream().anyMatch(block -> !area.contains(block))) return false;
        boolean toProduction = destination.stream().anyMatch(block -> farm.mechanism().contains(block) || isOutput(block));
        if (toProduction) return source.stream().allMatch(farm.mechanism()::contains)
                && source.stream().noneMatch(this::isOutput);
        return true; // Output -> drain is permitted, including a drain in the protected belt.
    }
}
