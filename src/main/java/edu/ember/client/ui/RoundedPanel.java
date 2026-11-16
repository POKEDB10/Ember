package edu.ember.client.ui;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Color;
import javax.swing.JPanel;

/** Paints a reusable antialiased rounded surface. @concept inheritance, overriding, Graphics2D */
public final class RoundedPanel extends JPanel {
    private final int radius;
    private Color fill;

    public RoundedPanel(int radius, Color fill) {
        this.radius = radius;
        this.fill = fill;
        setOpaque(false);
    }

    public void setFill(Color color) {
        fill = color;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D copy = (Graphics2D) graphics.create();
        copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        copy.setColor(fill);
        copy.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
        copy.dispose();
    }
}
