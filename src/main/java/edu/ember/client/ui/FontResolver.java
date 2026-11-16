package edu.ember.client.ui;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Set;

/** Selects the first installed Ember UI font with a portable fallback. @concept arrays, loops */
public final class FontResolver {
    private static final java.util.List<String> PREFERRED =
            java.util.List.of("Inter", "Segoe UI", "SF Pro Text");

    private FontResolver() {
    }

    public static Font resolve() {
        Set<String> installed = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames());
        for (String family : PREFERRED) {
            if (installed.contains(family)) {
                return new Font(family, Font.PLAIN, 14);
            }
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, 14);
    }
}
