package edu.ember.client.ui;

import java.awt.Font;

/** Returns Ember's retro monospace UI font. @concept logical fonts */
public final class FontResolver {
    private FontResolver() {
    }

    // The logical Monospaced font falls back per glyph, so Arabic and emoji still render.
    public static Font resolve() {
        return new Font(Font.MONOSPACED, Font.PLAIN, 14);
    }
}
