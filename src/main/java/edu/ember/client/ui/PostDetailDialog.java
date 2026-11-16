package edu.ember.client.ui;

import edu.ember.client.ClientController;
import edu.ember.common.model.SocialPost;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/** Collects a plain-text comment for a selected post. @concept dialogs, ActionListener */
public final class PostDetailDialog {
    private PostDetailDialog() { }

    public static void open(java.awt.Component parent, SocialPost post, ClientController controller) {
        open(parent, post, controller, () -> { });
    }

    public static void open(java.awt.Component parent, SocialPost post, ClientController controller,
            Runnable refresh) {
        java.awt.Window owner = SwingUtilities.getWindowAncestor(parent);
        JDialog dialog = new JDialog(owner, "Post details", java.awt.Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setLayout(new BorderLayout(8, 8));
        javax.swing.JTextArea commentList = new javax.swing.JTextArea(8, 40);
        commentList.setEditable(false);
        commentList.setLineWrap(true);
        commentList.setWrapStyleWord(true);
        commentList.setText(post.caption().isBlank() ? "Comments" : post.caption());
        dialog.add(new javax.swing.JScrollPane(commentList), BorderLayout.CENTER);
        JTextField comment = new JTextField(32);
        JButton send = new JButton("Comment");
        send.addActionListener(event -> {
            String text = comment.getText().strip();
            if (!text.isEmpty()) {
                controller.interactPost(post.postId(), "comment", text);
                refresh.run();
                dialog.dispose();
            }
        });
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING));
        actions.add(comment);
        actions.add(send);
        dialog.add(actions, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        edu.ember.client.ClientModelListener listener = (model, eventType, message) -> {
            if (eventType.equals("COMMENTS")) {
                StringBuilder text = new StringBuilder(post.caption());
                for (edu.ember.common.model.PostComment item : model.comments(post.postId())) {
                    text.append("\n\n@").append(item.authorUserId()).append(": ").append(item.text());
                }
                commentList.setText(text.toString());
            }
        };
        controller.addListener(listener);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent event) {
                controller.removeListener(listener);
            }
        });
        dialog.setVisible(true);
        controller.interactPost(post.postId(), "comments", "");
    }
}
