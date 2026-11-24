package org.lwjgl;

/** Minimal test-only stand-in for LWJGL's PointerBuffer. */
public final class PointerBuffer {

    private final long[] values;

    public PointerBuffer(long... values) {
        this.values = values.clone();
    }

    public long get(int index) {
        return values[index];
    }
}
