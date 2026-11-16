package edu.ember.client.ui;

import java.awt.Color;
import javax.swing.UIManager;

/** Applies Ember Night and Campus Daylight palette tokens to Swing. @concept enums, Strategy */
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
    }

    public static void applyToTree(java.awt.Component component) {
        if (component instanceof javax.swing.JComponent swing) {
            swing.setForeground(current.text());
            if (component instanceof javax.swing.JTextArea
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
        DARK(new Color(0x0F172A), new Color(0x1E293B), new Color(0x334155),
                new Color(0x6366F1), new Color(0xEC4899), new Color(0xF8FAFC),
                new Color(0x94A3B8), new Color(0x10B981), new Color(0x38BDF8)),
        LIGHT(new Color(0xF8FAFC), Color.WHITE, new Color(0xF1F5F9),
                new Color(0x4F46E5), new Color(0xDB2777), new Color(0x0F172A),
                new Color(0x64748B), new Color(0x059669), new Color(0x0284C7));

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
