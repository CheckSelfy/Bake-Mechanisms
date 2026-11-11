package io.github.checkselfy.bakemechanisms.marking;

import java.util.function.Consumer;
import org.bukkit.entity.Player;

/** Asks a player to type a farm name; the Paper implementation is {@link DialogTextPrompt}. */
@FunctionalInterface
public interface TextPrompt {
    /**
     * Shows a text field. Not for use inside an inventory click.
     *
     * @param initial text already in the field
     * @param submit  receives the typed text on the server thread, if the player confirms
     */
    void ask(Player player, String title, String label, String initial, Consumer<String> submit);
}
