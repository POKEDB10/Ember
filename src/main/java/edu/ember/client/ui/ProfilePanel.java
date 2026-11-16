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
import javax.swing.JCheckBox;
import javax.swing.border.EmptyBorder;

/** Edits the signed-in profile and follows a selected public account. @concept MVC, Swing forms */
public final class ProfilePanel extends JPanel implements ClientModelListener {
    private final ClientStateModel model;
    private final ClientController controller;
    private final JTextField displayName = new JTextField();
    private final JTextArea bio = new JTextArea(5, 30);
    private final JTextField target = new JTextField();
    private final JCheckBox privateAccount = new JCheckBox("Private account (approval required to follow)");
    private final JLabel counts = new JLabel("Your profile");

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
        privateAccount.setOpaque(false);
        fields.add(privateAccount);
        JButton save = new JButton("Save profile");
        save.addActionListener(event -> controller.updateProfile(displayName.getText(), bio.getText(),
                privateAccount.isSelected()));
        fields.add(save);
        fields.add(counts);
        fields.add(new JLabel("Account ID (for follow, block, report, or request review)"));
        JPanel followControls = new JPanel();
        followControls.setOpaque(false);
        followControls.add(target);
        JButton follow = new JButton("Follow");
        follow.addActionListener(event -> controller.follow(target.getText().strip(), true));
        JButton unfollow = new JButton("Unfollow");
        unfollow.addActionListener(event -> controller.follow(target.getText().strip(), false));
        followControls.add(follow);
        followControls.add(unfollow);
        JButton approve = new JButton("Approve request");
        approve.addActionListener(event -> controller.approveFollow(target.getText().strip(), true));
        JButton reject = new JButton("Reject request");
        reject.addActionListener(event -> controller.approveFollow(target.getText().strip(), false));
        JButton block = new JButton("Block");
        block.addActionListener(event -> controller.blockUser(target.getText().strip(), true));
        JButton unblock = new JButton("Unblock");
        unblock.addActionListener(event -> controller.blockUser(target.getText().strip(), false));
        followControls.add(approve);
        followControls.add(reject);
        followControls.add(block);
        followControls.add(unblock);
        fields.add(followControls);
        fields.add(new JLabel("Report type (profile, post, or message)"));
        JTextField reportType = new JTextField("profile");
        fields.add(reportType);
        fields.add(new JLabel("Reason (up to 300 characters)"));
        JTextField reportReason = new JTextField();
        fields.add(reportReason);
        JButton report = new JButton("Submit report for account ID above");
        report.addActionListener(event -> controller.reportObject(reportType.getText().strip(),
                target.getText().strip(), reportReason.getText()));
        fields.add(report);
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
                privateAccount.setSelected(profile.privateAccount());
            }
        }
    }

    public void applyTheme() {
        setBackground(ThemeManager.current().background());
        ThemeManager.applyToTree(this);
    }
}
