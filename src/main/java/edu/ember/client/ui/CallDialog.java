package edu.ember.client.ui;

import edu.ember.client.CallSession;
import edu.ember.client.ClientController;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Window;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

/**
 * The desktop call window: who is calling, the call time and delay, each person's safety code,
 * and Accept / Mute / Hang up. One window at a time; it closes itself shortly after the call ends.
 * @concept Swing, JDialog, Timer, Observer
 */
public final class CallDialog extends JDialog {
    private static CallDialog open;
    private final ClientController controller;
    private final JLabel heading = new JLabel();
    private final JLabel status = new JLabel();
    private final JTextArea codes = new JTextArea(4, 32);
    private final JButton accept = new JButton("Accept");
    private final JButton mute = new JButton("Mute");
    private final JButton hangUp = new JButton("Hang up");
    /** Command objects: the same Action can sit on a button, a menu or a key. */
    private final javax.swing.Action videoAction = new javax.swing.AbstractAction("Video on website") {
        @Override public void actionPerformed(java.awt.event.ActionEvent event) {
            openVideo();
        }
    };
    private final JButton video = new JButton(videoAction);
    private final Timer clock = new Timer(1000, event -> refresh());

    private CallDialog(Window owner, ClientController controller) {
        super(owner, "Ember call", ModalityType.MODELESS);
        this.controller = controller;
        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.setBorder(new EmptyBorder(14, 16, 14, 16));
        body.setBackground(ThemeManager.current().surface());
        heading.setFont(heading.getFont().deriveFont(java.awt.Font.BOLD, 16f));
        heading.setForeground(ThemeManager.current().text());
        status.setForeground(ThemeManager.current().muted());
        codes.setEditable(false);
        codes.setOpaque(false);
        codes.setForeground(ThemeManager.current().muted());
        codes.setLineWrap(true);
        codes.setWrapStyleWord(true);
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(heading, BorderLayout.NORTH);
        top.add(status, BorderLayout.SOUTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        buttons.setOpaque(false);
        buttons.add(accept);
        buttons.add(mute);
        buttons.add(video);
        buttons.add(hangUp);
        body.add(top, BorderLayout.NORTH);
        body.add(codes, BorderLayout.CENTER);
        body.add(buttons, BorderLayout.SOUTH);
        setContentPane(body);
        accept.addActionListener(event -> controller.acceptCall());
        mute.addActionListener(event -> {
            CallSession call = controller.call();
            if (call != null) {
                call.setMuted(!call.muted());
            }
        });
        hangUp.addActionListener(event -> controller.hangUp());
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent event) {
                controller.hangUp();
            }
        });
        clock.start();
    }

    /** Opens the website's "Join call" page; the call stays here until the browser takes it over. */
    private void openVideo() {
        String url = controller.handoffUrl();
        if (url == null) {
            return;
        }
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
            status.setText("Press Join call in the browser…");
        } catch (java.io.IOException | UnsupportedOperationException exception) {
            javax.swing.JOptionPane.showMessageDialog(this, "Open this address in your browser:\n" + url,
                    "Video on website", javax.swing.JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /** Called (on the Swing thread) whenever the call changes. */
    public static void update(Component anchor, ClientController controller) {
        CallSession call = controller.call();
        if (call == null) {
            return;
        }
        if (open == null) {
            open = new CallDialog(SwingUtilities.getWindowAncestor(anchor), controller);
            open.refresh();
            open.pack();
            open.setLocationRelativeTo(anchor);
            open.setVisible(true);
            if (call.state() == CallSession.State.RINGING_IN) {
                java.awt.Toolkit.getDefaultToolkit().beep();
            }
        }
        open.refresh();
    }

    private void refresh() {
        CallSession call = controller.call();
        CallSession shown = call == null ? controller.lastCall() : call;
        if (shown == null) {
            return;
        }
        heading.setText("📞 " + shown.title);
        String time = shown.startedMs() > 0 ? String.format("%d:%02d · ",
                (System.currentTimeMillis() - shown.startedMs()) / 60_000,
                (System.currentTimeMillis() - shown.startedMs()) / 1000 % 60) : "";
        status.setText(time + shown.status() + (shown.pingMs() >= 0 ? " · " + shown.pingMs() + " ms" : ""));
        StringBuilder text = new StringBuilder();
        shown.codes().forEach((user, code) -> {
            if (!code.isEmpty()) {
                text.append("Safety code with ").append(user).append(": ").append(code).append('\n');
            }
        });
        if (text.length() > 0) {
            text.append("Same codes on both screens = private call. Use headphones to avoid echo.");
        }
        if (controller.callSettings().statsOverlay() && shown.stats() != null) {
            text.append("\n\n").append(shown.stats().line());
        }
        codes.setText(text.toString());
        accept.setVisible(shown.state() == CallSession.State.RINGING_IN);
        mute.setText(shown.muted() ? "Unmute" : "Mute");
        mute.setEnabled(shown.state() == CallSession.State.ACTIVE);
        String handoff = call == null ? null : controller.handoffUrl();
        video.setVisible(handoff != null);
        videoAction.putValue(javax.swing.Action.SHORT_DESCRIPTION,
                "Continue this call with video in the browser; this window keeps the call until you join there");
        if (shown.state() == CallSession.State.ENDED) {
            hangUp.setEnabled(false);
            accept.setVisible(false);
            clock.stop();
            Timer close = new Timer(1500, event -> dispose());
            close.setRepeats(false);
            close.start();
            open = null;
        }
        pack();
    }
}
