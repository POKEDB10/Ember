package edu.ember.client.ui;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JLabel;

/** Draws an unread-count pill with a compact 99+ cap. @concept Swing component, overriding */
public final class BadgeLabel extends JLabel {
    private int count;

    public BadgeLabel(int count) {
        super();
        setCount(count);
        setForeground(ThemeManager.current().text());
        setHorizontalAlignment(CENTER);
        setOpaque(false);
    }

    public void setCount(int value) {
        count = Math.max(0, value);
        setText(count > 99 ? "99+" : Integer.toString(count));
        setVisible(count > 0);
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        if (count > 0) {
            Graphics2D copy = (Graphics2D) graphics.create();
            copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            copy.setColor(ThemeManager.current().accent());
            copy.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            copy.dispose();
        }
        super.paintComponent(graphics);
    }
}
