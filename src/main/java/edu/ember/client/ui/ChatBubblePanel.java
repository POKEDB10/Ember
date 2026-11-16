package edu.ember.client.ui;

import edu.ember.common.model.Message;
import edu.ember.common.model.MessageStatus;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Insets;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.border.EmptyBorder;

/** Shows a wrapped message bubble and receipt mark without HTML rendering. @concept Swing composition */
public final class ChatBubblePanel extends JPanel {
    private final RoundedPanel bubble;

    public ChatBubblePanel(Message message, boolean outgoing, String receiptTooltip) {
        super(new FlowLayout(outgoing ? FlowLayout.RIGHT : FlowLayout.LEFT, 8, 4));
        setOpaque(false);
        bubble = new RoundedPanel(16, outgoing ? ThemeManager.current().accent()
                : ThemeManager.current().surface());
        bubble.setLayout(new BorderLayout(4, 2));
        bubble.setBorder(new EmptyBorder(8, 12, 6, 12));
        JTextArea body = new JTextArea(message.body());
        body.setLineWrap(true);
        body.setWrapStyleWord(true);
        body.setColumns(34);
        body.setRows(Math.min(12, Math.max(1, (message.body().length() / 42) + 1)));
        body.setEditable(false);
        body.setFocusable(false);
        body.setOpaque(true);
        body.setBackground(outgoing ? ThemeManager.current().accent()
                : ThemeManager.current().surface());
        body.setBorder(null);
        body.setForeground(ThemeManager.current().text());
        body.setMargin(new Insets(0, 0, 0, 0));
        bubble.add(body, BorderLayout.CENTER);
        JLabel status = new JLabel(markFor(message.status()));
        status.setForeground(message.status() == MessageStatus.READ
                ? ThemeManager.current().read() : ThemeManager.current().muted());
        status.setHorizontalAlignment(JLabel.RIGHT);
        bubble.add(status, BorderLayout.SOUTH);
        if (receiptTooltip != null && !receiptTooltip.isBlank()) {
            bubble.setToolTipText(receiptTooltip);
        }
        add(bubble);
    }

    private static String markFor(MessageStatus status) {
        return switch (status) {
            case PENDING_LOCAL -> "◷";
            case SENT -> "✓";
            case DELIVERED -> "✓✓";
            case READ -> "✓✓";
            case FAILED -> "!";
        };
    }
}
