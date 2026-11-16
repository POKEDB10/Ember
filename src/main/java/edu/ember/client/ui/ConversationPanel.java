package edu.ember.client.ui;

import edu.ember.client.ClientController;
import edu.ember.client.ClientModelListener;
import edu.ember.client.ClientStateModel;
import edu.ember.common.model.Message;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import javax.swing.AbstractAction;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

/** Renders a conversation, receipt marks, typing state, and multiline composer. @concept Swing, MVC */
public final class ConversationPanel extends JPanel implements ClientModelListener {
    private final ClientController controller;
    private final JPanel header = new JPanel(new BorderLayout());
    private final JPanel composeArea = new JPanel(new BorderLayout(8, 8));
    private final JLabel heading = new JLabel("Choose a conversation");
    private final JLabel connection = new JLabel(" ");
    private final JPanel transcript = new JPanel();
    private final JTextArea composer = new JTextArea(3, 32);
    private final Timer typingEnd;
    private String conversationId;

    public ConversationPanel(ClientController controller) {
        super(new BorderLayout(8, 8));
        this.controller = controller;
        setBackground(ThemeManager.current().background());
        header.setBorder(new EmptyBorder(14, 18, 10, 18));
        header.setBackground(ThemeManager.current().surface());
        heading.setForeground(ThemeManager.current().text());
        heading.setFont(heading.getFont().deriveFont(java.awt.Font.BOLD, 16f));
        connection.setForeground(ThemeManager.current().muted());
        header.add(heading, BorderLayout.WEST);
        header.add(connection, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);
        transcript.setLayout(new BoxLayout(transcript, BoxLayout.Y_AXIS));
        transcript.setBackground(ThemeManager.current().background());
        JScrollPane scroll = new JScrollPane(transcript);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setBorder(null);
        add(scroll, BorderLayout.CENTER);
        composer.setLineWrap(true);
        composer.setWrapStyleWord(true);
        composer.setBackground(ThemeManager.current().surface());
        composer.setForeground(ThemeManager.current().text());
        composer.setBorder(new EmptyBorder(10, 12, 10, 12));
        composeArea.setBorder(new EmptyBorder(8, 12, 12, 12));
        composeArea.setOpaque(false);
        composeArea.add(new JScrollPane(composer), BorderLayout.CENTER);
        javax.swing.JButton send = new javax.swing.JButton("Send");
        send.addActionListener(event -> send());
        composeArea.add(send, BorderLayout.EAST);
        add(composeArea, BorderLayout.SOUTH);
        InputMap input = composer.getInputMap(JComponent.WHEN_FOCUSED);
        input.put(KeyStroke.getKeyStroke("ENTER"), "send-message");
        composer.getActionMap().put("send-message", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { send(); }
        });
        composer.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent event) { typing(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent event) { typing(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent event) { typing(); }
        });
        typingEnd = new Timer(1_100, event -> sendTyping(false));
        typingEnd.setRepeats(false);
        controller.addListener(this);
    }

    public void openConversation(String id) {
        conversationId = id;
        controller.openConversation(id);
        heading.setText(id);
        refreshMessages();
        composer.requestFocusInWindow();
    }

    public JTextArea composer() { return composer; }
    public JPanel transcriptContent() { return transcript; }

    @Override
    public void onModelChanged(ClientStateModel model, String eventType, Message message) {
        if (eventType.equals("MESSAGE") || eventType.equals("MESSAGE_STATUS")
                || eventType.equals("RECEIPT")) {
            if (message != null && message.conversationId().equals(conversationId)) {
                refreshMessages();
                if (eventType.equals("MESSAGE") && !message.senderUserId().equals(model.userId())
                        && message.conversationId().equals(model.activeConversationId())
                        && controller.readReceiptsEnabled()) {
                    controller.markRead(conversationId, message.clientMsgId());
                }
            }
        } else if (eventType.equals("TYPING") || eventType.equals("PRESENCE")) {
            updatePresence(model);
        } else if (eventType.equals("ERROR") && "503".equals(model.errorCode())) {
            connection.setText("Connection lost · reconnecting…");
        } else if (eventType.equals("AUTH_OK")) {
            updatePresence(model);
            connection.setText("Connected");
        }
    }

    private void send() {
        if (conversationId == null || composer.getText().isBlank()) {
            return;
        }
        sendTyping(false);
        controller.sendMessage(conversationId, composer.getText());
        composer.setText("");
    }

    private void typing() {
        if (conversationId != null && !composer.getText().isBlank()) {
            sendTyping(true);
            typingEnd.restart();
        }
    }

    private void sendTyping(boolean value) {
        if (conversationId != null) {
            controller.setTyping(conversationId, value);
        }
        if (!value) {
            typingEnd.stop();
        }
    }

    private void refreshMessages() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::refreshMessages);
            return;
        }
        transcript.removeAll();
        for (Message message : controller.model().messages(conversationId)) {
            ClientStateModel.ReceiptSummary receipt = controller.model()
                    .receiptSummary(message.clientMsgId());
            String tooltip = receipt == null ? "" : "Delivered " + receipt.delivered() + "/"
                    + receipt.total() + " · Read by " + receipt.readBy();
            JPanel row = new JPanel(new BorderLayout());
            row.setOpaque(false);
            row.add(new ChatBubblePanel(message,
                    message.senderUserId().equals(controller.model().userId()), tooltip),
                    BorderLayout.CENTER);
            java.awt.Dimension preferred = row.getPreferredSize();
            row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, preferred.height));
            row.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            transcript.add(row);
            transcript.add(Box.createVerticalStrut(3));
        }
        transcript.revalidate();
        transcript.repaint();
    }

    public void applyTheme() {
        setBackground(ThemeManager.current().background());
        header.setBackground(ThemeManager.current().surface());
        transcript.setBackground(ThemeManager.current().background());
        composer.setBackground(ThemeManager.current().elevated());
        heading.setForeground(ThemeManager.current().text());
        connection.setForeground(ThemeManager.current().muted());
        ThemeManager.applyToTree(this);
        refreshMessages();
    }

    private void updatePresence(ClientStateModel model) {
        if (conversationId == null) {
            return;
        }
        String typingUser = model.typingUser(conversationId);
        if (typingUser != null && !typingUser.equals(model.userId())) {
            connection.setText(typingUser + " is typing…");
            return;
        }
        if (conversationId.startsWith("dm:")) {
            String[] participants = conversationId.substring(3).split(":", -1);
            if (participants.length == 2) {
                String other = participants[0].equals(model.userId()) ? participants[1] : participants[0];
                connection.setText(model.isOnline(other) ? "Online" : "Offline");
            }
        }
    }
}
