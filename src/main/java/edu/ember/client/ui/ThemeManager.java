package edu.ember.client.ui;

import java.awt.Color;
import javax.swing.UIManager;

/** Applies the retro Ember Amber (dark) and Ember Paper (light) palettes to Swing. @concept enums, Strategy */
public final class ThemeManager {
    private static Theme current = Theme.DARK;

    private ThemeManager() {
    }

    public static Theme current() {
        return current;
    }

    public static void apply(Theme theme) {
        current = theme;
        UIManager.put("defaultFont", FontResolver.resolve());
        UIManager.put("Panel.background", theme.surface());
        UIManager.put("Label.foreground", theme.text());
        UIManager.put("TextField.background", theme.elevated());
        UIManager.put("TextField.foreground", theme.text());
        UIManager.put("TextArea.background", theme.elevated());
        UIManager.put("TextArea.foreground", theme.text());
        UIManager.put("Button.background", theme.elevated());
        UIManager.put("Button.foreground", theme.text());
        UIManager.put("RadioButton.background", theme.surface());
        UIManager.put("RadioButton.foreground", theme.text());
        UIManager.put("TextField.caretForeground", theme.text());
        UIManager.put("TextArea.caretForeground", theme.text());
        // Nimbus derives every painter colour from these base keys, so buttons, combos,
        // scrollbars and split panes all pick up the retro palette.
        UIManager.put("nimbusBase", theme.accent());
        UIManager.put("nimbusBlueGrey", theme.elevated());
        UIManager.put("control", theme.surface());
        UIManager.put("text", theme.text());
        UIManager.put("nimbusLightBackground", theme.elevated());
        UIManager.put("nimbusFocus", theme.social());
        UIManager.put("nimbusSelectionBackground", theme.accent());
        UIManager.put("nimbusSelectedText", theme.text());
        UIManager.put("info", theme.elevated());
        // Nimbus reads fonts from its own defaults table, not from developer overrides.
        UIManager.getLookAndFeelDefaults().put("defaultFont", FontResolver.resolve());
    }

    public static void applyToTree(java.awt.Component component) {
        if (component instanceof javax.swing.JComponent swing) {
            swing.setForeground(current.text());
            if (component instanceof javax.swing.JComboBox<?>) {
                // Nimbus paints combos with a light face in every palette, so they need dark ink.
                swing.setForeground(Theme.LIGHT.text());
            } else if (component instanceof javax.swing.JTextArea
                    || component instanceof javax.swing.JTextField
                    || component instanceof javax.swing.JPasswordField) {
                swing.setBackground(current.elevated());
            } else if (component instanceof javax.swing.JButton
                    || component instanceof javax.swing.JRadioButton) {
                swing.setBackground(current.elevated());
            } else if (component instanceof javax.swing.JList<?>) {
                swing.setBackground(current.surface());
            } else if (component instanceof javax.swing.JScrollPane) {
                swing.setBackground(current.surface());
            }
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                applyToTree(child);
            }
        }
    }

    public enum Theme {
        // Same tokens as the web app's amber and paper themes (src/main/resources/web/app.css).
        DARK(new Color(0x120B06), new Color(0x1B1109), new Color(0x28190C),
                new Color(0x7A2E0A), new Color(0xFF6A1A), new Color(0xFFB347),
                new Color(0xB5813F), new Color(0xA6E86F), new Color(0xFFE08A)),
        LIGHT(new Color(0xE9DFC8), new Color(0xF6EFDF), new Color(0xFFFAF0),
                new Color(0xF3D2A8), new Color(0xB8430B), new Color(0x2B1D10),
                new Color(0x6E5536), new Color(0x2F7A1F), new Color(0xB8430B));

        private final Color background;
        private final Color surface;
        private final Color elevated;
        private final Color accent;
        private final Color social;
        private final Color text;
        private final Color muted;
        private final Color online;
        private final Color read;

        Theme(Color background, Color surface, Color elevated, Color accent, Color social,
                Color text, Color muted, Color online, Color read) {
            this.background = background;
            this.surface = surface;
            this.elevated = elevated;
            this.accent = accent;
            this.social = social;
            this.text = text;
            this.muted = muted;
            this.online = online;
            this.read = read;
        }

        public Color background() { return background; }
        public Color surface() { return surface; }
        public Color elevated() { return elevated; }
        public Color accent() { return accent; }
        public Color social() { return social; }
        public Color text() { return text; }
        public Color muted() { return muted; }
        public Color online() { return online; }
        public Color read() { return read; }
    }
}
