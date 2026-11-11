package io.github.checkselfy.bakemechanisms.marking;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;

/**
 * The screen of {@link FarmScreens}: what each slot shows and does. Rendering it into an inventory is the opener's
 * job; its content can be replaced while it stays open.
 */
public final class FarmScreen extends ButtonMenu {
    static final int SIZE = 54;
    static final String TITLE = "Мои фермы";

    /** What a slot shows, independent of Paper's item implementation. */
    record Icon(Material material, String name, List<String> lore) {
        Icon {
            lore = List.copyOf(lore);
        }
    }

    /**
     * @param leaves whether the action leaves the screen; such a button latches it so it acts once
     */
    record Button(Runnable action, boolean leaves) { }

    private final Map<Integer, Icon> icons = new LinkedHashMap<>();
    private final Map<Integer, Button> buttons = new LinkedHashMap<>();

    Map<Integer, Icon> icons() {
        return Map.copyOf(icons);
    }

    /** Shows an icon that does nothing when clicked. */
    FarmScreen show(int slot, Icon icon) {
        icons.put(slot, icon);
        return this;
    }

    /** Shows an icon whose click runs an action that keeps the screen open. */
    FarmScreen button(int slot, Icon icon, Runnable action) {
        buttons.put(slot, new Button(action, false));
        return show(slot, icon);
    }

    /** Shows an icon whose click runs an action that leaves the screen. */
    FarmScreen exit(int slot, Icon icon, Runnable action) {
        buttons.put(slot, new Button(action, true));
        return show(slot, icon);
    }

    /** The button of a slot, or null when the slot has none. */
    Button button(int slot) {
        return buttons.get(slot);
    }

    /** Takes over the content of another screen, keeping this one open. */
    void replaceWith(FarmScreen other) {
        icons.clear();
        icons.putAll(other.icons);
        buttons.clear();
        buttons.putAll(other.buttons);
    }
}
