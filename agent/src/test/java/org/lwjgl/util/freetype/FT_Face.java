package org.lwjgl.util.freetype;

/** Minimal test-only stand-in for LWJGL's FT_Face. */
public final class FT_Face {

    private final long address;

    public FT_Face(long address) {
        this.address = address;
    }

    public long address() {
        return address;
    }
}
