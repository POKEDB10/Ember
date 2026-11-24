package edu.ember.client.ui;

import java.awt.GridLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

/**
 * Theme, read receipts, call settings, and a developer-only connection-drop control.
 * @concept Swing listeners (delegation event model), constructor overloading, layouts
 */
public final class SettingsPanel extends JPanel {
    public SettingsPanel(Runnable themeChanged, java.util.function.Consumer<Boolean> readReceipts,
            Runnable simulateNetworkDrop) {
        this(themeChanged, readReceipts, simulateNetworkDrop, null);
    }

    /** With call settings: quality mode, microphone, speaker, Speaker mode, stats overlay, calls v2. */
    public SettingsPanel(Runnable themeChanged, java.util.function.Consumer<Boolean> readReceipts,
            Runnable simulateNetworkDrop, edu.ember.client.call.CallSettings calls) {
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
        if (calls != null) {
            addCallSettings(calls);
        }
    }

    private void addCallSettings(edu.ember.client.call.CallSettings calls) {
        add(label("Call quality (this device)"));
        JComboBox<edu.ember.client.call.QualityMode> mode = new JComboBox<>(edu.ember.client.call.QualityMode.values());
        mode.setSelectedItem(calls.mode());
        mode.setToolTipText("Auto adapts to the connection; Max goes up to the camera; Data saver uses the least data");
        mode.addActionListener(event -> calls.setMode((edu.ember.client.call.QualityMode) mode.getSelectedItem()));
        add(mode);
        add(label("Microphone"));
        add(deviceBox(true, calls.microphone(), calls::setMicrophone));
        add(label("Speaker"));
        add(deviceBox(false, calls.speaker(), calls::setSpeaker));
        add(check("Speaker mode (reduce echo without headphones)", calls.speakerMode(), calls::setSpeakerMode));
        add(check("Show call stats", calls.statsOverlay(), calls::setStatsOverlay));
        add(check("Calls v2 (new voice engine; off = classic calls)", calls.callsV2(), calls::setCallsV2));
    }

    /** "System default" plus every device that can record (input) or play. */
    private JComboBox<String> deviceBox(boolean input, String current, java.util.function.Consumer<String> save) {
        JComboBox<String> box = new JComboBox<>();
        box.addItem("System default");
        edu.ember.client.call.AudioDevices.names(input).forEach(box::addItem);
        box.setSelectedItem(current.isEmpty() ? "System default" : current);
        box.addActionListener(event -> {
            String chosen = (String) box.getSelectedItem();
            save.accept("System default".equals(chosen) ? "" : chosen);
        });
        return box;
    }

    private JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(ThemeManager.current().text());
        return label;
    }

    private JCheckBox check(String text, boolean value, java.util.function.Consumer<Boolean> save) {
        JCheckBox box = new JCheckBox(text, value);
        box.setOpaque(false);
        box.setForeground(ThemeManager.current().text());
        box.addItemListener(event -> save.accept(box.isSelected()));
        return box;
    }

    public void applyTheme() {
        setBackground(ThemeManager.current().background());
        ThemeManager.applyToTree(this);
    }
}
