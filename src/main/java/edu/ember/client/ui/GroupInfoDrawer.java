package edu.ember.client.ui;

import edu.ember.client.ClientController;
import edu.ember.client.ClientModelListener;
import edu.ember.client.ClientStateModel;
import edu.ember.common.model.GroupChat;
import edu.ember.common.model.Message;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;

/** Shows group members and submits moderator membership actions. @concept JDialog, listeners, RBAC */
public final class GroupInfoDrawer extends JDialog implements ClientModelListener {
    private final ClientController controller;
    private final String groupId;
    private final JTextArea details = new JTextArea();

    public GroupInfoDrawer(java.awt.Window owner, ClientController controller, String groupId) {
        super(owner, "Group information", ModalityType.MODELESS);
        this.controller = controller;
        this.groupId = groupId;
        details.setEditable(false);
        JTextField target = new JTextField(18);
        JComboBox<String> action = new JComboBox<>(new String[] {"ADD", "REMOVE", "PROMOTE"});
        JButton apply = new JButton("Apply member action");
        apply.addActionListener(event -> controller.changeGroupMember(groupId,
                target.getText().strip(), (String) action.getSelectedItem()));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING));
        actions.add(new JLabel("Account ID"));
        actions.add(target);
        actions.add(action);
        actions.add(apply);
        setLayout(new BorderLayout(8, 8));
        add(new JLabel("Members and roles"), BorderLayout.NORTH);
        add(new javax.swing.JScrollPane(details), BorderLayout.CENTER);
        add(actions, BorderLayout.SOUTH);
        setSize(720, 420);
        setLocationRelativeTo(owner);
        controller.addListener(this);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent event) {
                controller.removeListener(GroupInfoDrawer.this);
            }
        });
        controller.requestGroupInfo(groupId);
    }

    @Override public void onModelChanged(ClientStateModel model, String eventType, Message message) {
        if (!eventType.equals("GROUP_UPDATE")) { return; }
        GroupChat group = model.groupDetails(groupId);
        if (group == null) { return; }
        StringBuilder text = new StringBuilder(group.name()).append("\n")
                .append(group.description()).append("\n\n");
        group.members().forEach((id, role) -> text.append(id).append(" · ")
                .append(role.name()).append('\n'));
        details.setText(text.toString());
    }
}
