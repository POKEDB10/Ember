package edu.ember.client.ui;

import edu.ember.client.ClientController;
import edu.ember.client.ClientModelListener;
import edu.ember.client.ClientStateModel;
import edu.ember.client.SearchEngine;
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
    private final JLabel pinnedBanner = new JLabel(" ");
    private final javax.swing.JButton createPollButton = new javax.swing.JButton("Create poll");
    private final javax.swing.JButton groupInfoButton = new javax.swing.JButton("Group info");
    private final javax.swing.JButton safetyButton = new javax.swing.JButton("🔒 Safety number");
    private final javax.swing.JTextField searchField = new javax.swing.JTextField(14);
    private final SearchEngine searchEngine = new SearchEngine();
    private final JLabel replyIndicator = new JLabel();
    private final JPanel transcript = new JPanel();
    private final JTextArea composer = new JTextArea(3, 32);
    private final javax.swing.JComboBox<String> expiry = new javax.swing.JComboBox<>(
            new String[] {"No expiry", "15 seconds"});
    private final Timer typingEnd;
    private String conversationId;
    private Message replyTarget;

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
        JPanel headerTools = new JPanel(new FlowLayout(FlowLayout.TRAILING, 8, 0));
        headerTools.setOpaque(false);
        searchField.setToolTipText("Search this conversation");
        javax.swing.JButton search = new javax.swing.JButton("Find");
        search.addActionListener(event -> refreshMessages());
        searchField.addActionListener(event -> refreshMessages());
        headerTools.add(searchField);
        headerTools.add(search);
        createPollButton.addActionListener(event -> createPoll());
        createPollButton.setVisible(false);
        headerTools.add(createPollButton);
        groupInfoButton.addActionListener(event -> {
            if (conversationId != null && conversationId.startsWith("grp:")) {
                new GroupInfoDrawer(javax.swing.SwingUtilities.getWindowAncestor(this), controller,
                        conversationId.substring(4)).setVisible(true);
            }
        });
        groupInfoButton.setVisible(false);
        headerTools.add(groupInfoButton);
        safetyButton.setToolTipText("Check that this chat is end-to-end encrypted");
        safetyButton.addActionListener(event -> showSafetyNumber());
        safetyButton.setVisible(false);
        headerTools.add(safetyButton);
        headerTools.add(connection);
        header.add(headerTools, BorderLayout.EAST);
        pinnedBanner.setForeground(ThemeManager.current().muted());
        pinnedBanner.setBorder(new EmptyBorder(0, 18, 8, 18));
        header.add(pinnedBanner, BorderLayout.SOUTH);
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
        replyIndicator.setForeground(ThemeManager.current().muted());
        replyIndicator.setBorder(new EmptyBorder(0, 10, 0, 0));
        replyIndicator.setVisible(false);
        composeArea.add(replyIndicator, BorderLayout.NORTH);
        composeArea.add(new JScrollPane(composer), BorderLayout.CENTER);
        JPanel sendControls = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
        sendControls.setOpaque(false);
        expiry.setToolTipText("Disappearing message lifetime starts when the server accepts it");
        sendControls.add(expiry);
        javax.swing.JButton send = new javax.swing.JButton("Send");
        send.addActionListener(event -> send());
        sendControls.add(send);
        composeArea.add(sendControls, BorderLayout.EAST);
        add(composeArea, BorderLayout.SOUTH);
        InputMap input = composer.getInputMap(JComponent.WHEN_FOCUSED);
        input.put(KeyStroke.getKeyStroke("ENTER"), "send-message");
        composer.getActionMap().put("send-message", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { send(); }
        });
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke("control F"), "focus-search");
        getActionMap().put("focus-search", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                searchField.requestFocusInWindow();
                searchField.selectAll();
            }
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
        heading.setText(ChatListPanel.peerName(id, controller.model().userId()));
        createPollButton.setVisible(id.startsWith("grp:"));
        groupInfoButton.setVisible(id.startsWith("grp:"));
        safetyButton.setVisible(id.startsWith("dm:"));
        refreshMessages();
        composer.requestFocusInWindow();
    }

    /** Shows the direct chat's safety number; it must match the one on the other person's screen. */
    private void showSafetyNumber() {
        String id = conversationId;
        if (id == null || !id.startsWith("dm:")) {
            return;
        }
        String[] parts = id.split(":");
        String other = parts[1].equals(controller.model().userId()) ? parts[2] : parts[1];
        new javax.swing.SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                return controller.safetyNumber(other);
            }

            @Override protected void done() {
                String text;
                try {
                    String number = get();
                    text = number.isEmpty() ? other + " has no encryption keys yet (they have not opened the new app)."
                            : "Safety number with " + other + ":\n\n" + number
                                    + "\n\nCompare it with " + other + " in person. The same digits mean nobody,"
                                    + " not even the server, can read this chat.";
                } catch (Exception exception) {
                    text = "Could not check encryption right now.";
                }
                javax.swing.JOptionPane.showMessageDialog(ConversationPanel.this, text, "End-to-end encryption",
                        javax.swing.JOptionPane.INFORMATION_MESSAGE);
            }
        }.execute();
    }

    public JTextArea composer() { return composer; }
    public JPanel transcriptContent() { return transcript; }

    @Override
    public void onModelChanged(ClientStateModel model, String eventType, Message message) {
        if (eventType.equals("MESSAGE") || eventType.equals("MESSAGE_STATUS")
                || eventType.equals("MESSAGE_REACTION") || eventType.equals("RECEIPT")) {
            if (message != null && message.conversationId().equals(conversationId)) {
                refreshMessages();
                if (eventType.equals("MESSAGE") && !message.senderUserId().equals(model.userId())
                        && message.conversationId().equals(model.activeConversationId())
                        && controller.readReceiptsEnabled()) {
                    controller.markRead(conversationId, message.clientMsgId());
                }
            }
        } else if (eventType.equals("MESSAGE_PINNED") || eventType.equals("MESSAGE_STARRED")) {
            if (message != null && message.conversationId().equals(conversationId)) refreshMessages();
        } else if (eventType.equals("POLL_UPDATE")) {
            refreshMessages();
        } else if (eventType.equals("MESSAGE_EXPIRED")) {
            refreshMessages();
        } else if (eventType.equals("MESSAGE_EDITED") || eventType.equals("MESSAGE_DELETED")) {
            refreshMessages();
        } else if (eventType.equals("MESSAGE_EXPIRED")) {
            refreshMessages();
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
        int ttlSec = expiry.getSelectedIndex() == 1 ? 15 : 0;
        if (replyTarget == null) {
            controller.sendMessage(conversationId, composer.getText(), ttlSec);
        } else {
            controller.sendReply(conversationId, composer.getText(), ttlSec,
                    replyTarget.clientMsgId());
            replyTarget = null;
            replyIndicator.setText("");
            replyIndicator.setVisible(false);
        }
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
        java.util.List<Message> allMessages = controller.model().messages(conversationId);
        String query = searchField.getText().strip();
        java.util.List<Message> visibleMessages = query.isEmpty() ? allMessages
                : searchEngine.search(allMessages, query);
        String pinned = allMessages.stream().filter(Message::pinned).reduce((first, second) -> second)
                .map(message -> "Pinned: " + message.body()).orElse(" ");
        pinnedBanner.setText(pinned.length() > 90 ? pinned.substring(0, 90) + "…" : pinned);
        for (Message message : visibleMessages) {
            ClientStateModel.ReceiptSummary receipt = controller.model()
                    .receiptSummary(message.clientMsgId());
            String tooltip = receipt == null ? "" : "Delivered " + receipt.delivered() + "/"
                    + receipt.total() + " · Read by " + receipt.readBy();
            JPanel row = new JPanel(new BorderLayout());
            row.setOpaque(false);
            row.add(new ChatBubblePanel(message,
                    message.senderUserId().equals(controller.model().userId()), tooltip,
                    this::selectReply,
                    (target, emoji) -> controller.reactMessage(target.clientMsgId(), emoji, false),
                    this::editMessage, this::deleteMessage, this::forwardMessage),
                    BorderLayout.CENTER);
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
            actions.setOpaque(false);
            javax.swing.JButton pin = new javax.swing.JButton(message.pinned() ? "Unpin" : "Pin");
            pin.addActionListener(event -> controller.pinMessage(message.clientMsgId(), !message.pinned()));
            boolean starred = message.starredBy(controller.model().userId());
            javax.swing.JButton star = new javax.swing.JButton(starred ? "Unstar" : "Star");
            star.addActionListener(event -> controller.starMessage(message.clientMsgId(), !starred));
            actions.add(pin);
            actions.add(star);
            row.add(actions, BorderLayout.SOUTH);
            java.awt.Dimension preferred = row.getPreferredSize();
            row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, preferred.height));
            row.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            transcript.add(row);
            transcript.add(Box.createVerticalStrut(3));
        }
        if (conversationId != null && conversationId.startsWith("grp:")) {
            for (edu.ember.common.model.PollData poll : controller.model()
                    .polls(conversationId.substring(4))) {
                JPanel pollPanel = new JPanel(new java.awt.BorderLayout(4, 4));
                pollPanel.setBorder(javax.swing.BorderFactory.createTitledBorder(poll.question()));
                JPanel options = new JPanel(new FlowLayout(FlowLayout.LEADING));
                java.util.List<Integer> totals = poll.totals();
                for (int index = 0; index < poll.options().size(); index++) {
                    final int option = index;
                    javax.swing.JButton vote = new javax.swing.JButton(poll.options().get(index)
                            + " · " + totals.get(index));
                    vote.addActionListener(event -> controller.votePoll(poll.pollId(), option));
                    options.add(vote);
                }
                pollPanel.add(options, BorderLayout.CENTER);
                pollPanel.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 90));
                transcript.add(pollPanel);
            }
        }
        transcript.revalidate();
        transcript.repaint();
    }

    private void selectReply(Message message) {
        replyTarget = message;
        String preview = message.body().substring(0, Math.min(72, message.body().length()));
        replyIndicator.setText("Replying to " + message.senderUserId() + ": " + preview);
        replyIndicator.setVisible(true);
        composer.requestFocusInWindow();
    }

    private void editMessage(Message message) {
        String updated = javax.swing.JOptionPane.showInputDialog(this, "Edit message",
                message.body());
        if (updated != null && !updated.isBlank()) {
            controller.editMessage(message.clientMsgId(), updated);
        }
    }

    private void deleteMessage(Message message) {
        int choice = javax.swing.JOptionPane.showConfirmDialog(this,
                "Delete this message for everyone?", "Delete message",
                javax.swing.JOptionPane.YES_NO_OPTION);
        if (choice == javax.swing.JOptionPane.YES_OPTION) {
            controller.deleteMessage(message.clientMsgId());
        }
    }

    private void forwardMessage(Message message) {
        java.util.List<String> targets = controller.model().conversationIds().stream()
                .filter(id -> !id.equals(message.conversationId())).sorted().toList();
        if (targets.isEmpty()) {
            javax.swing.JOptionPane.showMessageDialog(this, "Open a second conversation first");
            return;
        }
        Object selected = javax.swing.JOptionPane.showInputDialog(this, "Forward to conversation",
                "Forward message", javax.swing.JOptionPane.PLAIN_MESSAGE, null,
                targets.toArray(), targets.get(0));
        if (selected instanceof String target) {
            controller.forwardMessage(message.clientMsgId(), target);
        }
    }

    private void createPoll() {
        if (conversationId == null || !conversationId.startsWith("grp:")) { return; }
        String question = javax.swing.JOptionPane.showInputDialog(this, "Poll question");
        if (question == null || question.isBlank()) { return; }
        String enteredOptions = javax.swing.JOptionPane.showInputDialog(this,
                "Enter 2–6 options, one per line", "Yes\nNo");
        if (enteredOptions == null) { return; }
        java.util.List<String> options = enteredOptions.lines().map(String::strip)
                .filter(option -> !option.isBlank()).toList();
        if (options.size() < 2 || options.size() > 6) {
            javax.swing.JOptionPane.showMessageDialog(this, "Polls need 2–6 non-empty options");
            return;
        }
        controller.createPoll(conversationId.substring(4), question, options);
    }

    public void applyTheme() {
        setBackground(ThemeManager.current().background());
        header.setBackground(ThemeManager.current().surface());
        transcript.setBackground(ThemeManager.current().background());
        composer.setBackground(ThemeManager.current().elevated());
        heading.setForeground(ThemeManager.current().text());
        connection.setForeground(ThemeManager.current().muted());
        pinnedBanner.setForeground(ThemeManager.current().muted());
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
