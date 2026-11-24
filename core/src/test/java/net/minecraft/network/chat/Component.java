package net.minecraft.network.chat;

/** Test-only stand-in; only what {@code Notify} reflects on. */
public class Component {

    private final String text;

    protected Component(String text) {
        this.text = text;
    }

    public static MutableComponent literal(String text) {
        return new MutableComponent(text);
    }

    public String getString() {
        return text;
    }

    public static class MutableComponent extends Component {

        MutableComponent(String text) {
            super(text);
        }
    }
}
