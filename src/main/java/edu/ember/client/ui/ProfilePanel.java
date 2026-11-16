package edu.ember.client.ui;

import edu.ember.client.ClientController;
import edu.ember.client.ClientModelListener;
import edu.ember.client.ClientStateModel;
import edu.ember.common.model.Message;
import edu.ember.common.model.UserProfile;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;

/** Edits the signed-in profile and follows a selected public account. @concept MVC, Swing forms */
public final class ProfilePanel extends JPanel implements ClientModelListener {
    private final ClientStateModel model;
    private final ClientController controller;
    private final JTextField displayName = new JTextField();
    private final JTextArea bio = new JTextArea(5, 30);
    private final JTextField target = new JTextField();
    private final JLabel counts = new JLabel("Your public profile");

    public ProfilePanel(ClientController controller) {
        super(new BorderLayout(10, 10));
        this.controller = controller;
        model = controller.model();
        setBorder(new EmptyBorder(24, 28, 24, 28));
        setBackground(ThemeManager.current().background());
        JPanel fields = new JPanel(new GridLayout(0, 1, 8, 8));
        fields.setOpaque(false);
        fields.add(new JLabel("Display name"));
        fields.add(displayName);
        fields.add(new JLabel("Bio (280 characters)"));
        bio.setLineWrap(true);
        bio.setWrapStyleWord(true);
        fields.add(new javax.swing.JScrollPane(bio));
        JButton save = new JButton("Save profile");
        save.addActionListener(event -> controller.updateProfile(displayName.getText(), bio.getText()));
        fields.add(save);
        fields.add(counts);
        fields.add(new JLabel("Follow or unfollow by username"));
        JPanel followControls = new JPanel();
        followControls.setOpaque(false);
        followControls.add(target);
        JButton follow = new JButton("Follow");
        follow.addActionListener(event -> controller.follow(target.getText().strip(), true));
        JButton unfollow = new JButton("Unfollow");
        unfollow.addActionListener(event -> controller.follow(target.getText().strip(), false));
        followControls.add(follow);
        followControls.add(unfollow);
        fields.add(followControls);
        add(fields, BorderLayout.NORTH);
        controller.addListener(this);
    }

    @Override public void onModelChanged(ClientStateModel changed, String eventType, Message message) {
        if (eventType.equals("AUTH_OK") && changed.userId() != null) {
            controller.requestProfile(changed.userId());
        } else if (eventType.equals("PROFILE")) {
            UserProfile profile = changed.profile(changed.userId());
            if (profile != null) {
                displayName.setText(profile.displayName());
                bio.setText(profile.bio());
            }
        }
    }

    public void applyTheme() {
        setBackground(ThemeManager.current().background());
        ThemeManager.applyToTree(this);
    }
}
