package net.minecraft.client.gui.screens;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.network.chat.Component;

/** Test-only stand-in that records what it was built with. */
public class ConfirmScreen extends Screen {

    public final BooleanConsumer callback;
    public final Component title;
    public final Component message;
    public final Component yesButton;
    public final Component noButton;

    public ConfirmScreen(BooleanConsumer callback, Component title, Component message,
            Component yesButton, Component noButton) {
        this.callback = callback;
        this.title = title;
        this.message = message;
        this.yesButton = yesButton;
        this.noButton = noButton;
    }
}
