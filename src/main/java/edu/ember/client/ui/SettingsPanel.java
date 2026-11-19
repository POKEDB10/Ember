package edu.ember.client.ui;

import java.awt.GridLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
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
        JComboBox<ThemeManager.Theme> themes = new JComboBox<>(ThemeManager.Theme.values());
        themes.setSelectedItem(ThemeManager.current());
        themes.getAccessibleContext().setAccessibleName("Theme");
        themes.addActionListener(event -> {
            ThemeManager.apply((ThemeManager.Theme) themes.getSelectedItem());
            themeChanged.run();
        });
        add(new JLabel("Theme"));
        add(themes);
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
