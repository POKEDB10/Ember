package edu.ember.client.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

/** Displays a timed in-app notification banner. @concept Swing Timer, event listener */
public final class ToastBanner extends JPanel {
    private final JLabel message = new JLabel();
    private final Timer timer;

    public ToastBanner() {
        super(new BorderLayout());
        setBorder(new EmptyBorder(10, 14, 10, 14));
        setBackground(ThemeManager.current().elevated());
        message.setForeground(ThemeManager.current().text());
        add(message, BorderLayout.CENTER);
        setVisible(false);
        timer = new Timer(3_500, event -> setVisible(false));
        timer.setRepeats(false);
    }

    public void showMessage(String text) {
        message.setText(text);
        setVisible(true);
        timer.restart();
    }

    public void stop() {
        timer.stop();
    }
}
