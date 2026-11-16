package edu.ember.client.ui;

import edu.ember.common.model.StoryItem;
import java.awt.BorderLayout;
import java.io.ByteArrayInputStream;
import javax.swing.ImageIcon;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.SwingWorker;
import javax.swing.Timer;

/** Displays an expiring story image and closes when the story expires. @concept JDialog, SwingWorker, Timer */
public final class StoryViewerDialog extends JDialog {
    public StoryViewerDialog(java.awt.Window owner, StoryItem story, byte[] imageBytes) {
        super(owner, "Campus story", ModalityType.MODELESS);
        setLayout(new BorderLayout(8, 8));
        JLabel image = new JLabel("Loading story…", JLabel.CENTER);
        JLabel caption = new JLabel(story.caption(), JLabel.CENTER);
        JLabel remaining = new JLabel(" ", JLabel.CENTER);
        add(image, BorderLayout.CENTER);
        add(caption, BorderLayout.NORTH);
        add(remaining, BorderLayout.SOUTH);
        new SwingWorker<ImageIcon, Void>() {
            @Override protected ImageIcon doInBackground() throws Exception {
                java.awt.image.BufferedImage source = javax.imageio.ImageIO.read(
                        new ByteArrayInputStream(imageBytes));
                if (source == null) { throw new java.io.IOException("Story image is invalid"); }
                double scale = Math.min(1.0, Math.min(700.0 / source.getWidth(),
                        560.0 / source.getHeight()));
                int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
                int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
                java.awt.image.BufferedImage resized = new java.awt.image.BufferedImage(width,
                        height, java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D graphics = resized.createGraphics();
                graphics.drawImage(source, 0, 0, width, height, null);
                graphics.dispose();
                return new ImageIcon(resized);
            }

            @Override protected void done() {
                try { image.setIcon(get()); image.setText(""); }
                catch (Exception exception) { image.setText("Story image could not be displayed"); }
            }
        }.execute();
        Timer timer = new Timer(1_000, event -> {
            long seconds = Math.max(0, (story.expiresAtMs() - System.currentTimeMillis() + 999) / 1000);
            remaining.setText("Expires in " + seconds + " s");
            if (seconds == 0) { dispose(); }
        });
        timer.start();
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent event) { timer.stop(); }
        });
        setSize(760, 680);
        setLocationRelativeTo(owner);
    }
}
