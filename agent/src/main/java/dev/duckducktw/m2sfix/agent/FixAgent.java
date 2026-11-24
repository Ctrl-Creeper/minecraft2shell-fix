package dev.duckducktw.m2sfix.agent;

import dev.duckducktw.m2sfix.core.FaceGuard;
import dev.duckducktw.m2sfix.core.FixPolicy;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Java agent entry point: {@code -javaagent:minecraft2shell-fix-agent.jar}.
 *
 * <p>Adds one transformer that patches exactly two methods of LWJGL's
 * {@code FreeType} bindings (see {@link FixTransformer}). Nothing else in the
 * JVM is touched, so the same agent works for the vanilla client, Fabric,
 * Forge, NeoForge and any launcher that lets you pass JVM arguments.</p>
 *
 * <p>Every failure path disables the guard and logs once: a mitigation that
 * breaks the client would be worse than no mitigation at all.</p>
 */
public final class FixAgent {

    private static final AtomicBoolean FAILED = new AtomicBoolean(false);

    private FixAgent() {
    }

    public static void premain(String args, Instrumentation instrumentation) {
        try {
            if (instrumentation == null) {
                fail("Instrumentation is null");
                return;
            }
            System.err.println("[m2sfix] agent active (policy=" + FixPolicy.policy() + ")");
            FixTransformer transformer = new FixTransformer();
            instrumentation.addTransformer(transformer, true);
            retransformIfAlreadyLoaded(instrumentation);
        } catch (Throwable throwable) {
            fail("agent installation failed: " + safeMessage(throwable));
        }
    }

    /**
     * LWJGL classes are usually not loaded yet at {@code premain} time, but an
     * agent can also be attached at runtime, so loaded copies are re-transformed
     * once to be safe.
     */
    private static void retransformIfAlreadyLoaded(Instrumentation instrumentation) {
        try {
            if (!instrumentation.isRetransformClassesSupported()) {
                return;
            }
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (FixTransformer.TARGET_BINARY_NAME.equals(loaded.getName())
                        && instrumentation.isModifiableClass(loaded)) {
                    instrumentation.retransformClasses(loaded);
                    System.err.println("[m2sfix] retransformed already loaded FreeType binding");
                    return;
                }
            }
        } catch (Throwable throwable) {
            fail("retransformation of an already loaded FreeType binding failed: "
                    + safeMessage(throwable));
        }
    }

    private static void fail(String message) {
        if (FAILED.compareAndSet(false, true)) {
            System.err.println("[m2sfix] " + message + " - guard disabled");
        }
        FaceGuard.disableForFailure();
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
