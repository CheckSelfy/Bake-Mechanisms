package io.github.checkselfy.bakemechanisms.marking;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;

/**
 * One screen of {@link FarmScreens}: the list of a player's farms, one farm with its actions, or the confirmation of
 * its deletion. It holds what each slot shows and does; rendering it into an inventory is the opener's job.
 */
public final class FarmScreen extends ButtonMenu {
    static final int SIZE = 54;

    enum Kind { LIST, FARM, CONFIRM_DELETE }

    /** What a slot shows, independent of Paper's item implementation. */
    record Icon(Material material, String name, List<String> lore) {
        Icon {
            lore = List.copyOf(lore);
        }
    }

    private final Kind kind;
    private final String title;
    /** The farm of a FARM or CONFIRM_DELETE screen; null for the list. */
    private final UUID farm;
    private final Map<Integer, Icon> icons = new LinkedHashMap<>();
    private final Map<Integer, Runnable> actions = new LinkedHashMap<>();

    FarmScreen(Kind kind, String title, UUID farm) {
        this.kind = kind;
        this.title = title;
        this.farm = farm;
    }

    String title() {
        return title;
    }

    Kind kind() {
        return kind;
    }

    UUID farm() {
        return farm;
    }

    Map<Integer, Icon> icons() {
        return Map.copyOf(icons);
    }

    /** Shows an icon that does nothing when clicked. */
    FarmScreen show(int slot, Icon icon) {
        icons.put(slot, icon);
        return this;
    }

    /** Shows an icon whose click runs the action. */
    FarmScreen button(int slot, Icon icon, Runnable action) {
        actions.put(slot, action);
        return show(slot, icon);
    }

    /** The action of a slot, or null when the slot has no button. */
    Runnable action(int slot) {
        return actions.get(slot);
    }
}
