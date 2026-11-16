package edu.ember.client.ui;

import edu.ember.client.ClientController;
import edu.ember.client.ClientModelListener;
import edu.ember.client.ClientStateModel;
import edu.ember.common.model.Message;
import java.awt.BorderLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;

/** Shows moderation reports and offers the account-unlock action. @concept Swing, observer pattern */
public final class AdminConsolePanel extends JPanel implements ClientModelListener {
    private final ClientController controller;
    private final JTextArea reportList = new JTextArea();
    private final JTextField username = new JTextField(20);

    public AdminConsolePanel(ClientController controller) {
        super(new BorderLayout(8, 8));
        this.controller = controller;
        setBorder(new EmptyBorder(20, 24, 20, 24));
        reportList.setEditable(false);
        JPanel actions = new JPanel();
        JButton refresh = new JButton("Refresh moderation queue");
        refresh.addActionListener(event -> controller.requestAdminData());
        JButton unlock = new JButton("Unlock username");
        unlock.addActionListener(event -> controller.unlockUser(username.getText().strip()));
        actions.add(refresh);
        actions.add(new JLabel("Locked username"));
        actions.add(username);
        actions.add(unlock);
        add(actions, BorderLayout.NORTH);
        add(new JScrollPane(reportList), BorderLayout.CENTER);
        controller.addListener(this);
    }

    @Override public void onModelChanged(ClientStateModel model, String eventType, Message message) {
        if (eventType.equals("ADMIN_DATA")) {
            StringBuilder text = new StringBuilder("Moderation reports: ")
                    .append(model.moderationReports().size()).append('\n');
            model.moderationReports().forEach(report -> text.append(report.targetType())
                    .append(" ").append(report.targetId()).append(" · ")
                    .append(report.reason()).append(" · by ").append(report.reporterUserId()).append('\n'));
            text.append("\nLocked usernames: ").append(String.join(", ", model.lockedUsers()));
            reportList.setText(text.toString());
        }
    }
}
