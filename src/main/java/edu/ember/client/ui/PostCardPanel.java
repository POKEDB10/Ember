package edu.ember.client.ui;

import edu.ember.client.ClientController;
import edu.ember.common.model.SocialPost;
import java.awt.BorderLayout;
import java.awt.Dimension;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.border.EmptyBorder;

/** Shows one public post and its basic interactions. @concept Swing composition, Observer pattern */
public final class PostCardPanel extends JPanel {
    public PostCardPanel(SocialPost post, javax.swing.ImageIcon imageIcon,
            ClientController controller, Runnable refresh) {
        super(new BorderLayout(8, 8));
        setBackground(ThemeManager.current().surface());
        setBorder(new EmptyBorder(12, 12, 12, 12));
        JLabel image = new JLabel("Loading image…", JLabel.CENTER);
        image.setPreferredSize(new Dimension(360, 240));
        image.setOpaque(true);
        image.setBackground(ThemeManager.current().elevated());
        if (imageIcon != null) {
            image.setText("");
            image.setIcon(imageIcon);
        }
        add(image, BorderLayout.NORTH);
        JTextArea caption = new JTextArea(post.caption());
        caption.setEditable(false);
        caption.setLineWrap(true);
        caption.setWrapStyleWord(true);
        caption.setOpaque(false);
        add(caption, BorderLayout.CENTER);
        JPanel actions = new JPanel();
        actions.setOpaque(false);
        JButton like = new JButton((post.likedByViewer() ? "Unlike" : "Like") + " · " + post.likeCount());
        like.addActionListener(event -> controller.interactPost(post.postId(),
                post.likedByViewer() ? "unlike" : "like", ""));
        like.addActionListener(event -> refresh.run());
        JButton comments = new JButton("Comments · " + post.commentCount());
        comments.addActionListener(event -> PostDetailDialog.open(this, post, controller, refresh));
        JButton profile = new JButton("@" + post.ownerUserId());
        profile.addActionListener(event -> controller.requestProfile(post.ownerUserId()));
        actions.add(profile);
        actions.add(like);
        actions.add(comments);
        add(actions, BorderLayout.SOUTH);
    }
}
