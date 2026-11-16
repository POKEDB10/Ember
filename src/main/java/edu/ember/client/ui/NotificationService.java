package edu.ember.client.ui;

import java.awt.Component;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

/** Sends a tray notification when available and otherwise shows an in-app toast. @concept Strategy, fallback */
public final class NotificationService {
    private NotificationService() {
    }

    public static void show(Component parent, ToastBanner fallback, String title, String message) {
        if (SystemTray.isSupported()) {
            try {
                TrayIcon icon = new TrayIcon(createIcon(), "Ember");
                icon.setImageAutoSize(true);
                SystemTray.getSystemTray().add(icon);
                icon.displayMessage(title, message, TrayIcon.MessageType.INFO);
                javax.swing.Timer remove = new javax.swing.Timer(5_000, event ->
                        SystemTray.getSystemTray().remove(icon));
                remove.setRepeats(false);
                remove.start();
                return;
            } catch (java.awt.AWTException | UnsupportedOperationException
                    | SecurityException exception) {
                fallback.showMessage(title + ": " + message);
                return;
            }
        }
        fallback.showMessage(title + ": " + message);
    }

    private static BufferedImage createIcon() {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        graphics.setColor(ThemeManager.current().accent());
        graphics.fillRoundRect(1, 1, 30, 30, 10, 10);
        graphics.setColor(ThemeManager.current().text());
        graphics.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 19));
        graphics.drawString("E", 8, 23);
        graphics.dispose();
        return image;
    }
}
