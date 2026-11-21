package edu.ember.client.ui;

import edu.ember.client.ClientController;
import edu.ember.client.ClientModelListener;
import edu.ember.client.ClientStateModel;
import edu.ember.common.model.Message;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.border.EmptyBorder;

/** Lists and filters conversations, ordered by latest server sequence. @concept Comparator, Observer */
public final class ChatListPanel extends JPanel implements ClientModelListener {
    private final ClientController controller;
    private final Consumer<String> onOpen;
    private final JTextField search = new JTextField("Search conversations…");
    private final JPanel conversationRows = new JPanel();
    private final JPanel title = new JPanel(new FlowLayout(FlowLayout.LEFT));
    private int visibleRows;
    private final Set<String> conversations = new LinkedHashSet<>();

    public ChatListPanel(ClientController controller, Consumer<String> onOpen) {
        super(new BorderLayout(8, 8));
        this.controller = controller;
        this.onOpen = onOpen;
        setBorder(new EmptyBorder(12, 12, 12, 12));
        setBackground(ThemeManager.current().surface());
        title.setOpaque(false);
        title.add(new javax.swing.JLabel("Chats"));
        add(title, BorderLayout.NORTH);
        conversationRows.setLayout(new javax.swing.BoxLayout(conversationRows,
                javax.swing.BoxLayout.Y_AXIS));
        conversationRows.setBackground(ThemeManager.current().surface());
        JPanel content = new JPanel(new BorderLayout(6, 6));
        content.setOpaque(false);
        content.add(search, BorderLayout.NORTH);
        content.add(new JScrollPane(conversationRows), BorderLayout.CENTER);
        add(content, BorderLayout.CENTER);
        search.setToolTipText("Search conversations");
        search.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent event) {
                if (search.getText().equals("Search conversations…")) {
                    search.setText("");
                }
            }
        });
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { rebuild(); }
            @Override public void removeUpdate(DocumentEvent event) { rebuild(); }
            @Override public void changedUpdate(DocumentEvent event) { rebuild(); }
        });
        controller.addListener(this);
        for (String conversation : controller.model().conversationIds()) {
            conversations.add(conversation);
        }
        rebuild();
    }

    @Override
    public void onModelChanged(ClientStateModel model, String eventType, Message message) {
        if (eventType.equals("MESSAGE") && message != null) {
            conversations.add(message.conversationId());
            rebuild();
        }
    }

    public JTextField searchField() { return search; }
    public int visibleConversationCount() { return visibleRows; }

    public void applyTheme() {
        setBackground(ThemeManager.current().surface());
        title.setBackground(ThemeManager.current().surface());
        conversationRows.setBackground(ThemeManager.current().surface());
        rebuild();
    }

    private void rebuild() {
        String query = search.getText().equals("Search conversations…")
                ? "" : search.getText().trim().toLowerCase(java.util.Locale.ROOT);
        var sorted = conversations.stream().sorted(Comparator.comparingLong(
                (String id) -> controller.model().messages(id).stream()
                        .mapToLong(Message::globalSequence).max().orElse(0)).reversed()).toList();
        conversationRows.removeAll();
        visibleRows = 0;
        for (String id : sorted) {
            if (query.isEmpty() || id.toLowerCase(java.util.Locale.ROOT).contains(query)) {
                conversationRows.add(createConversationRow(id));
                visibleRows++;
            }
        }
        conversationRows.revalidate();
        conversationRows.repaint();
    }

    private JButton createConversationRow(String conversationId) {
        String peer = peerName(conversationId, controller.model().userId());
        JButton row = new JButton();
        row.setHorizontalAlignment(JButton.LEFT);
        row.setLayout(new BorderLayout(10, 4));
        row.setBorder(new EmptyBorder(6, 6, 6, 6));
        row.setBackground(ThemeManager.current().surface());
        row.setForeground(ThemeManager.current().text());
        row.add(new AvatarComponent(peer, controller.model().isOnline(peer)), BorderLayout.WEST);
        JPanel details = new JPanel(new BorderLayout(0, 3));
        details.setOpaque(false);
        javax.swing.JLabel name = new javax.swing.JLabel(peer);
        name.setForeground(ThemeManager.current().text());
        name.setFont(name.getFont().deriveFont(java.awt.Font.BOLD));
        String latest = controller.model().messages(conversationId).stream()
                .max(Comparator.comparingLong(Message::globalSequence))
                .map(Message::body).orElse("");
        javax.swing.JLabel preview = new javax.swing.JLabel(latest);
        preview.setForeground(ThemeManager.current().muted());
        details.add(name, BorderLayout.NORTH);
        details.add(preview, BorderLayout.CENTER);
        row.add(details, BorderLayout.CENTER);
        row.add(new BadgeLabel(0), BorderLayout.EAST);
        row.addActionListener(event -> onOpen.accept(conversationId));
        row.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() == 2) {
                    onOpen.accept(conversationId);
                }
            }
        });
        row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 62));
        return row;
    }

    static String peerName(String conversationId, String userId) {
        if (conversationId.startsWith("dm:")) {
            String[] participants = conversationId.substring(3).split(":", -1);
            if (participants.length == 2) {
                return participants[0].equals(userId) ? participants[1] : participants[0];
            }
        }
        return "Study group";
    }
}
