package dev.duckducktw.m2sfix.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shows the in-game warning when a malicious font was caught.
 *
 * <p>Everything here is reflection on purpose: the same code runs inside the javaagent (which must
 * not link against Minecraft classes) and inside the mod, and no Minecraft compile-time dependency
 * is wanted in {@code core}. The dialog is the vanilla {@code ConfirmScreen} with two buttons —
 * "continue anyway" and "disconnect" — because it already looks like a Minecraft dialog and needs no
 * screen implementation of our own.</p>
 *
 * <p>Failure is always silent: if anything at all goes wrong (unknown Minecraft version, headless
 * run, missing class loader) the warning degrades to a chat message, then to a log line, and never
 * throws into the game.</p>
 */
public final class Notify {

    private static final AtomicBoolean SHOWN_FOR_SESSION = new AtomicBoolean(false);

    private static final String CLS_MINECRAFT = "net.minecraft.client.Minecraft";
    private static final String CLS_COMPONENT = "net.minecraft.network.chat.Component";
    private static final String CLS_SCREEN = "net.minecraft.client.gui.screens.Screen";
    private static final String CLS_CONFIRM = "net.minecraft.client.gui.screens.ConfirmScreen";
    private static final String CLS_BOOLEAN_CONSUMER = "it.unimi.dsi.fastutil.booleans.BooleanConsumer";

    private Notify() {
    }

    /**
     * Warns the player that a font from the server's resource pack was blocked.
     *
     * @param commandHint the command the font would have run, or {@code null} when unknown
     */
    public static void warnAboutBlockedFont(String commandHint) {
        try {
            if (!FixPolicy.notifyEnabled()) {
                return;
            }
            String body = body(commandHint);
            if (showConfirmScreen(body)) {
                return;
            }
            log(body);
        } catch (Throwable ignored) {
            // A warning is never worth breaking a client over.
        }
    }

    /** Test hook: forgets that a warning was already shown. */
    public static void resetForTests() {
        SHOWN_FOR_SESSION.set(false);
    }

    /** @return {@code true} when the modal dialog was installed */
    private static boolean showConfirmScreen(String body) {
        try {
            ClassLoader loader = MinecraftAccess.classLoader();
            if (loader == null) {
                return false;
            }
            Class<?> minecraft = Class.forName(CLS_MINECRAFT, false, loader);
            Object client = minecraft.getMethod("getInstance").invoke(null);
            if (client == null) {
                return false;
            }

            Class<?> component = Class.forName(CLS_COMPONENT, false, loader);
            Class<?> screen = Class.forName(CLS_SCREEN, false, loader);
            Class<?> confirm = Class.forName(CLS_CONFIRM, false, loader);
            Class<?> booleanConsumer = Class.forName(CLS_BOOLEAN_CONSUMER, false, loader);

            Object title = literal(component, "minecraft2shell-fix");
            Object message = literal(component, body);
            Object yes = literal(component, "我了解，繼續遊玩 / Continue");
            Object no = literal(component, "中斷連線 / Disconnect");

            Constructor<?> constructor = confirm.getConstructor(
                    booleanConsumer, component, component, component, component);
            Object dialog = constructor.newInstance(disconnectCallback(minecraft, client, booleanConsumer),
                    title, message, yes, no);

            Runnable show = () -> {
                try {
                    minecraft.getMethod("setScreenAndShow", screen).invoke(client, dialog);
                } catch (Throwable ignored) {
                    // Fall through to the log fallback below.
                }
            };

            if (SHOWN_FOR_SESSION.compareAndSet(false, true)) {
                runOnClientThread(minecraft, client, show);
                return true;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Builds the {@code BooleanConsumer} that the confirm screen calls back with: {@code true} for
     * "continue", {@code false} for "disconnect".
     */
    private static Object disconnectCallback(Class<?> minecraft, Object client, Class<?> booleanConsumer) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("accept".equals(method.getName()) && args != null && args.length == 1
                    && Boolean.FALSE.equals(args[0])) {
                try {
                    minecraft.getMethod("disconnectWithSavingScreen").invoke(client);
                } catch (Throwable ignored) {
                    try {
                        minecraft.getMethod("disconnect").invoke(client);
                    } catch (Throwable ignoredToo) {
                        // Nothing else we can do; staying connected is the safe default.
                    }
                }
            }
            return defaultValue(method.getReturnType());
        };
        return Proxy.newProxyInstance(booleanConsumer.getClassLoader(),
                new Class<?>[]{booleanConsumer}, handler);
    }

    private static void runOnClientThread(Class<?> minecraft, Object client, Runnable action) {
        try {
            Method execute = minecraft.getMethod("execute", Runnable.class);
            execute.invoke(client, action);
        } catch (Throwable ignored) {
            action.run();
        }
    }

    private static Object literal(Class<?> component, String value) {
        try {
            Method literal = component.getMethod("literal", String.class);
            return literal.invoke(null, value);
        } catch (Throwable ignored) {
            // Newer/older naming: try the interface method on MutableComponent.
            try {
                Method literal = Class.forName("net.minecraft.network.chat.MutableComponent",
                                false, component.getClassLoader())
                        .getMethod("literal", String.class);
                return literal.invoke(null, value);
            } catch (Throwable ignoredToo) {
                return null;
            }
        }
    }

    private static String body(String commandHint) {
        boolean known = commandHint != null && !commandHint.isBlank();
        String command = known
                ? commandHint
                : "（無法辨識：這個樣本沒把指令用明文寫在字型裡 / not stored in plain text）";
        return "檢測到伺服器的資源包疑似包含惡意內容（minecraft2shell），會在你的電腦執行指令：\n"
                + command + "\n"
                + "已經攔截，指令沒有被執行。\n\n"
                + "This server's resource pack looks malicious (minecraft2shell). The command above was "
                + "blocked and never ran.";
    }

    private static void log(String body) {
        System.err.println("[m2sfix] " + body.replace("\n", " | "));
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }

    /** Locates the Minecraft class loader without linking against Minecraft. */
    private static final class MinecraftAccess {

        private MinecraftAccess() {
        }

        static ClassLoader classLoader() {
            try {
                return Class.forName(CLS_MINECRAFT, false, Notify.class.getClassLoader())
                        .getClassLoader();
            } catch (Throwable ignored) {
                return null;
            }
        }
    }
}
