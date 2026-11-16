package edu.ember.client.ui;

import edu.ember.client.ClientController;
import edu.ember.client.ClientModelListener;
import edu.ember.client.ClientStateModel;
import edu.ember.common.model.Message;
import edu.ember.common.model.SocialPost;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;

/** Presents the feed and moves image file work off the event dispatch thread. @concept SwingWorker, Observer */
public final class CampusPulsePanel extends JPanel implements ClientModelListener {
    private final ClientController controller;
    private final ClientStateModel model;
    private final JPanel cards = new JPanel();
    private final JTextField caption = new JTextField(30);
    private final JLabel status = new JLabel("Public campus feed");
    private final Set<String> requestedMedia = new HashSet<>();
    private final Set<String> decodingMedia = new HashSet<>();
    private final java.util.Map<String, javax.swing.ImageIcon> scaledImages = new java.util.HashMap<>();
    private String uploadedMediaId = "";

    public CampusPulsePanel(ClientController controller) {
        super(new BorderLayout(10, 10));
        this.controller = controller;
        model = controller.model();
        setBorder(new EmptyBorder(14, 18, 14, 18));
        setBackground(ThemeManager.current().background());
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEADING));
        controls.setOpaque(false);
        JButton choose = new JButton("Choose photo");
        choose.addActionListener(event -> choosePhoto());
        JButton create = new JButton("Create post");
        create.addActionListener(event -> createPost());
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(event -> controller.requestFeed(null));
        controls.add(choose);
        controls.add(caption);
        controls.add(create);
        controls.add(refresh);
        controls.add(status);
        cards.setLayout(new javax.swing.BoxLayout(cards, javax.swing.BoxLayout.Y_AXIS));
        cards.setBackground(ThemeManager.current().background());
        add(controls, BorderLayout.NORTH);
        add(new JScrollPane(cards), BorderLayout.CENTER);
        controller.addListener(this);
    }

    private void choosePhoto() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) { return; }
        Path path = chooser.getSelectedFile().toPath();
        status.setText("Validating image…");
        new SwingWorker<byte[], Void>() {
            @Override protected byte[] doInBackground() throws Exception {
                long size = Files.size(path);
                if (size < 1 || size > 5L * 1024 * 1024) {
                    throw new java.io.IOException("Choose a non-empty image no larger than 5 MB");
                }
                return Files.readAllBytes(path);
            }

            @Override protected void done() {
                try {
                    controller.uploadMedia(get(), path.getFileName().toString());
                    status.setText("Uploading image…");
                } catch (Exception exception) {
                    status.setText("Image could not be opened: " + exception.getMessage());
                }
            }
        }.execute();
    }

    private void createPost() {
        if (uploadedMediaId.isBlank()) {
            status.setText("Choose a photo before creating a post");
            return;
        }
        controller.createPost(uploadedMediaId, caption.getText());
        uploadedMediaId = "";
        caption.setText("");
        status.setText("Creating post…");
    }

    @Override public void onModelChanged(ClientStateModel changed, String eventType, Message message) {
        if (eventType.equals("AUTH_OK")) {
            controller.requestFeed(null);
        } else if (eventType.equals("MEDIA_UPLOADED")) {
            uploadedMediaId = changed.lastUploadedMediaId();
            status.setText("Photo ready · " + uploadedMediaId.substring(0, 8));
        } else if (eventType.equals("SOCIAL_FEED") || eventType.equals("MEDIA")) {
            renderPosts();
        }
    }

    private void renderPosts() {
        cards.removeAll();
        for (SocialPost post : model.socialPosts()) {
            byte[] bytes = model.mediaBytes(post.mediaId());
            if (bytes == null && requestedMedia.add(post.mediaId())) {
                controller.requestMedia(post.mediaId(), true);
            }
            if (bytes != null && !scaledImages.containsKey(post.mediaId())
                    && decodingMedia.add(post.mediaId())) {
                scaleImageInBackground(post.mediaId(), bytes);
            }
            cards.add(new PostCardPanel(post, scaledImages.get(post.mediaId()), controller,
                    () -> controller.requestFeed(null)));
        }
        if (model.socialPosts().isEmpty()) {
            cards.add(new JLabel("No posts yet. Share a photo with your campus."));
        }
        cards.revalidate();
        cards.repaint();
    }

    private void scaleImageInBackground(String mediaId, byte[] encoded) {
        new SwingWorker<javax.swing.ImageIcon, Void>() {
            @Override protected javax.swing.ImageIcon doInBackground() throws Exception {
                BufferedImage source = javax.imageio.ImageIO.read(new ByteArrayInputStream(encoded));
                if (source == null) { throw new java.io.IOException("Unsupported image data"); }
                double scale = Math.min(1.0, Math.min(360.0 / source.getWidth(),
                        240.0 / source.getHeight()));
                int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
                int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
                BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = scaled.createGraphics();
                graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.drawImage(source, 0, 0, width, height, null);
                graphics.dispose();
                return new javax.swing.ImageIcon(scaled);
            }

            @Override protected void done() {
                decodingMedia.remove(mediaId);
                try {
                    scaledImages.put(mediaId, get());
                    renderPosts();
                } catch (Exception exception) {
                    status.setText("Could not display a feed image");
                }
            }
        }.execute();
    }

    public void applyTheme() {
        setBackground(ThemeManager.current().background());
        cards.setBackground(ThemeManager.current().background());
        ThemeManager.applyToTree(this);
    }
}
