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
        this(message, outgoing, receiptTooltip, null, null, null, null, null);
    }

    public ChatBubblePanel(Message message, boolean outgoing, String receiptTooltip,
            java.util.function.Consumer<Message> replyHandler,
            java.util.function.BiConsumer<Message, String> reactionHandler) {
        this(message, outgoing, receiptTooltip, replyHandler, reactionHandler, null, null, null);
    }

    public ChatBubblePanel(Message message, boolean outgoing, String receiptTooltip,
            java.util.function.Consumer<Message> replyHandler,
            java.util.function.BiConsumer<Message, String> reactionHandler,
            java.util.function.Consumer<Message> editHandler,
            java.util.function.Consumer<Message> deleteHandler) {
        this(message, outgoing, receiptTooltip, replyHandler, reactionHandler,
                editHandler, deleteHandler, null);
    }

    public ChatBubblePanel(Message message, boolean outgoing, String receiptTooltip,
            java.util.function.Consumer<Message> replyHandler,
            java.util.function.BiConsumer<Message, String> reactionHandler,
            java.util.function.Consumer<Message> editHandler,
            java.util.function.Consumer<Message> deleteHandler,
            java.util.function.Consumer<Message> forwardHandler) {
        super(new FlowLayout(outgoing ? FlowLayout.RIGHT : FlowLayout.LEFT, 8, 4));
        setOpaque(false);
        bubble = new RoundedPanel(16, outgoing ? ThemeManager.current().accent()
                : ThemeManager.current().surface());
        bubble.setLayout(new BorderLayout(4, 2));
        bubble.setBorder(new EmptyBorder(8, 12, 6, 12));
        addMessageDetails(message);
        addMessageBody(message, outgoing);
        bubble.add(createFooter(message, outgoing, replyHandler, reactionHandler,
                editHandler, deleteHandler, forwardHandler), BorderLayout.SOUTH);
        if (receiptTooltip != null && !receiptTooltip.isBlank()) { bubble.setToolTipText(receiptTooltip); }
        add(bubble);
    }

    private void addMessageDetails(Message message) {
        JPanel details = new JPanel();
        details.setOpaque(false);
        details.setLayout(new javax.swing.BoxLayout(details, javax.swing.BoxLayout.Y_AXIS));
        if (!message.replyPreview().isBlank()) {
            JLabel quote = new JLabel("↳ " + message.replyPreview());
            quote.setForeground(ThemeManager.current().muted());
            details.add(quote);
        }
        if (message.expiresAtMs() > 0) { addExpiryCountdown(message, details); }
        if (details.getComponentCount() > 0) { bubble.add(details, BorderLayout.NORTH); }
    }

    private void addExpiryCountdown(Message message, JPanel details) {
        JLabel countdown = new JLabel();
        countdown.setForeground(ThemeManager.current().muted());
        details.add(countdown);
        javax.swing.Timer timer = new javax.swing.Timer(1_000, event -> {
            long remaining = Math.max(0, message.expiresAtMs() - System.currentTimeMillis());
            countdown.setText("Disappears in " + ((remaining + 999) / 1000) + "s");
            if (remaining == 0) { ((javax.swing.Timer) event.getSource()).stop(); }
        });
        timer.setInitialDelay(0);
        timer.start();
    }

    private void addMessageBody(Message message, boolean outgoing) {
        JTextArea body = new JTextArea(message.body());
        body.setLineWrap(true);
        body.setWrapStyleWord(true);
        body.setColumns(34);
        body.setRows(Math.min(12, Math.max(1, (message.body().length() / 42) + 1)));
        body.setEditable(false);
        body.setFocusable(false);
        body.setBackground(outgoing ? ThemeManager.current().accent() : ThemeManager.current().surface());
        body.setBorder(null);
        body.setForeground(ThemeManager.current().text());
        body.setMargin(new Insets(0, 0, 0, 0));
        bubble.add(body, BorderLayout.CENTER);
    }

    private JPanel createFooter(Message message, boolean outgoing,
            java.util.function.Consumer<Message> replyHandler,
            java.util.function.BiConsumer<Message, String> reactionHandler,
            java.util.function.Consumer<Message> editHandler,
            java.util.function.Consumer<Message> deleteHandler,
            java.util.function.Consumer<Message> forwardHandler) {
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.TRAILING, 2, 0));
        footer.setOpaque(false);
        JLabel status = new JLabel(markFor(message.status()));
        status.setForeground(message.status() == MessageStatus.READ
                ? ThemeManager.current().read() : ThemeManager.current().muted());
        footer.add(status);
        addButton(footer, "Reply", message, replyHandler);
        addButton(footer, "Forward", message, forwardHandler);
        addReactions(footer, message, reactionHandler);
        if (outgoing) {
            addButton(footer, "Edit", message, editHandler);
            addButton(footer, "Delete", message, deleteHandler);
        }
        addReactionSummary(footer, message);
        return footer;
    }

    private void addButton(JPanel footer, String label, Message message,
            java.util.function.Consumer<Message> action) {
        if (action == null) { return; }
        javax.swing.JButton button = new javax.swing.JButton(label);
        button.setMargin(new Insets(1, 4, 1, 4));
        button.addActionListener(event -> action.accept(message));
        footer.add(button);
    }

    private void addReactions(JPanel footer, Message message,
            java.util.function.BiConsumer<Message, String> action) {
        if (action == null) { return; }
        for (String emoji : new String[] {"👍", "❤️", "😂", "😮", "😢", "🔥"}) {
            javax.swing.JButton button = new javax.swing.JButton(emoji);
            button.setMargin(new Insets(1, 3, 1, 3));
            button.addActionListener(event -> action.accept(message, emoji));
            footer.add(button);
        }
    }

    private void addReactionSummary(JPanel footer, Message message) {
        java.util.Map<String, Integer> counts = message.reactionCounts();
        if (counts.isEmpty()) { return; }
        JLabel reactions = new JLabel(counts.entrySet().stream()
                .map(entry -> entry.getKey() + " " + entry.getValue())
                .collect(java.util.stream.Collectors.joining("  ")));
        reactions.setForeground(ThemeManager.current().muted());
        footer.add(reactions, 0);
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
