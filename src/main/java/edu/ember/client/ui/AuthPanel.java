package edu.ember.client.ui;

import edu.ember.common.util.AppInfo;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Arrays;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;

/** Collects registration and login fields and delegates authentication. @concept Swing, ActionListener */
public final class AuthPanel extends JPanel {
    private final JTextField username = new JTextField(20);
    private final JPasswordField password = new JPasswordField(20);
    private final JLabel error = new JLabel(" ");
    private final AuthActions actions;

    public AuthPanel(AuthActions actions) {
        super(new GridBagLayout());
        this.actions = actions;
        setBackground(ThemeManager.current().background());
        JPanel card = new JPanel(new GridBagLayout());
        card.setBackground(ThemeManager.current().surface());
        card.setBorder(new EmptyBorder(28, 32, 28, 32));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.gridwidth = 2;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(7, 4, 7, 4);
        JLabel title = new JLabel(AppInfo.NAME);
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 24f));
        title.setForeground(ThemeManager.current().text());
        card.add(title, constraints);
        constraints.gridy++;
        JLabel tagline = new JLabel(AppInfo.TAGLINE);
        tagline.setForeground(ThemeManager.current().muted());
        card.add(tagline, constraints);
        constraints.gridwidth = 1;
        constraints.gridy++;
        card.add(label("Username"), constraints);
        constraints.gridx = 1;
        card.add(username, constraints);
        constraints.gridx = 0;
        constraints.gridy++;
        card.add(label("Password"), constraints);
        constraints.gridx = 1;
        card.add(password, constraints);
        JButton login = new JButton("Log in");
        JButton register = new JButton("Create account");
        constraints.gridx = 0;
        constraints.gridy++;
        card.add(login, constraints);
        constraints.gridx = 1;
        card.add(register, constraints);
        constraints.gridx = 0;
        constraints.gridy++;
        constraints.gridwidth = 2;
        card.add(error, constraints);
        login.addActionListener(event -> submit(false));
        register.addActionListener(event -> submit(true));
        add(card);
    }

    public void setError(String message) {
        error.setText(message == null || message.isBlank() ? " " : message);
        error.setForeground(new Color(0xEF4444));
    }

    public String username() { return username.getText().trim(); }
    public void setUsername(String value) { username.setText(value); }

    public void applyTheme() {
        setBackground(ThemeManager.current().background());
        ThemeManager.applyToTree(this);
    }

    private JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(ThemeManager.current().text());
        return label;
    }

    private void submit(boolean register) {
        char[] secret = password.getPassword();
        try {
            if (register) {
                actions.register(username(), secret);
            } else {
                actions.login(username(), secret);
            }
        } finally {
            Arrays.fill(secret, '\0');
            password.setText("");
        }
    }

    public interface AuthActions {
        void login(String username, char[] password);
        void register(String username, char[] password);
    }
}
