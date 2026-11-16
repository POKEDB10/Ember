package edu.ember.client.ui;

import java.awt.GridLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.border.EmptyBorder;

/** Provides theme selection and a developer-only connection-drop control. @concept Swing listeners */
public final class SettingsPanel extends JPanel {
    public SettingsPanel(Runnable themeChanged, java.util.function.Consumer<Boolean> readReceipts,
            Runnable simulateNetworkDrop) {
        super(new GridLayout(0, 1, 8, 8));
        setBorder(new EmptyBorder(20, 24, 20, 24));
        setBackground(ThemeManager.current().background());
        JLabel title = new JLabel("Settings");
        title.setForeground(ThemeManager.current().text());
        add(title);
        JRadioButton dark = new JRadioButton("Ember Night", true);
        JRadioButton light = new JRadioButton("Campus Daylight");
        dark.setOpaque(false);
        light.setOpaque(false);
        dark.setForeground(ThemeManager.current().text());
        light.setForeground(ThemeManager.current().text());
        ButtonGroup themes = new ButtonGroup();
        themes.add(dark);
        themes.add(light);
        dark.addActionListener(event -> {
            ThemeManager.apply(ThemeManager.Theme.DARK);
            themeChanged.run();
        });
        light.addActionListener(event -> {
            ThemeManager.apply(ThemeManager.Theme.LIGHT);
            themeChanged.run();
        });
        add(dark);
        add(light);
        JCheckBox receipts = new JCheckBox("Send read receipts", true);
        receipts.setOpaque(false);
        receipts.setForeground(ThemeManager.current().text());
        receipts.addItemListener(event -> readReceipts.accept(receipts.isSelected()));
        add(receipts);
        JButton drop = new JButton("Simulate network drop");
        drop.setToolTipText("Developer control: close the transport to exercise reconnect");
        drop.addActionListener(event -> simulateNetworkDrop.run());
        add(drop);
    }

    public void applyTheme() {
        setBackground(ThemeManager.current().background());
        ThemeManager.applyToTree(this);
    }
}
