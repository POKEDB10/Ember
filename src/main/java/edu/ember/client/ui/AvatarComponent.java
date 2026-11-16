package edu.ember.client.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JComponent;

/** Paints initials and an online dot as a circular avatar. @concept custom Swing painting */
public final class AvatarComponent extends JComponent {
    private final String initials;
    private boolean online;

    public AvatarComponent(String displayName, boolean online) {
        initials = initialsOf(displayName);
        this.online = online;
        setPreferredSize(new java.awt.Dimension(44, 44));
        setToolTipText(displayName);
    }

    public void setOnline(boolean value) {
        online = value;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D copy = (Graphics2D) graphics.create();
        copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int diameter = Math.min(getWidth(), getHeight()) - 4;
        copy.setColor(ThemeManager.current().accent());
        copy.fillOval(2, 2, diameter, diameter);
        copy.setColor(ThemeManager.current().text());
        copy.setFont(getFont().deriveFont(Font.BOLD, 14f));
        java.awt.FontMetrics metrics = copy.getFontMetrics();
        copy.drawString(initials, (getWidth() - metrics.stringWidth(initials)) / 2,
                (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent());
        if (online) {
            copy.setColor(ThemeManager.current().online());
            copy.fillOval(getWidth() - 14, getHeight() - 14, 12, 12);
        }
        copy.dispose();
    }

    private static String initialsOf(String name) {
        String cleaned = name == null ? "?" : name.trim();
        if (cleaned.isEmpty()) {
            return "?";
        }
        String[] words = cleaned.split("\\s+");
        String first = words[0].substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        return words.length == 1 ? first : first + words[words.length - 1]
                .substring(0, 1).toUpperCase(java.util.Locale.ROOT);
    }
}
