package io.github.checkselfy.bakemechanisms.marking;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/**
 * {@link TextPrompt} as a Paper dialog: a text field as long as a farm name may be, with OK and Cancel. The typed
 * text reaches the server through a one-use click callback, never through a command line the client builds.
 */
public final class DialogTextPrompt implements TextPrompt {
    private static final String FIELD = "text";
    /** The callback may arrive off the server thread; this runs the submission on it. */
    private final Consumer<Runnable> serverThread;

    public DialogTextPrompt(Consumer<Runnable> serverThread) {
        this.serverThread = serverThread;
    }

    @Override
    public void ask(Player player, String title, String label, String initial, Consumer<String> submit) {
        var ok = ActionButton.builder(Component.text("Готово", NamedTextColor.GREEN))
                .action(DialogAction.customClick((view, audience) -> {
                    var text = view.getText(FIELD);
                    serverThread.accept(() -> submit.accept(text == null ? "" : text.strip()));
                }, ClickCallback.Options.builder().uses(1).build()))
                .build();
        var cancel = ActionButton.builder(Component.text("Отмена")).build();
        var dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text(title))
                        .inputs(List.of(DialogInput.text(FIELD, Component.text(label))
                                .initial(initial)
                                .maxLength(FarmNames.MAX_LENGTH)
                                .build()))
                        .build())
                .type(DialogType.confirmation(ok, cancel)));
        player.closeInventory();
        player.showDialog(dialog);
    }
}
