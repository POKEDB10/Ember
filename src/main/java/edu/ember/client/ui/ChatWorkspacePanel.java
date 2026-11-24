package edu.ember.client.ui;

import edu.ember.client.ClientController;
import edu.ember.client.ClientModelListener;
import edu.ember.client.ClientStateModel;
import edu.ember.common.model.Message;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

/** Composes the navigation rail, chat list, conversation, and settings screens. @concept CardLayout, MVC */
public final class ChatWorkspacePanel extends JPanel implements ClientModelListener {
    private final CardLayout pages = new CardLayout();
    private final JPanel content = new JPanel(pages);
    private final ChatListPanel chatList;
    private final ConversationPanel conversation;
    private final ToastBanner toast = new ToastBanner();
    private final JPanel rail = new JPanel();
    private final SettingsPanel settings;
    private final CampusPulsePanel campusPulse;
    private final ProfilePanel profile;
    private final AdminConsolePanel adminConsole;
    private final JButton admin = new JButton("Admin");
    private final ClientController controller;

    public ChatWorkspacePanel(ClientController controller, Runnable themeChanged,
            java.util.function.Consumer<Boolean> readReceipts, Runnable simulateNetworkDrop,
            Consumer<String> openConversation) {
        super(new BorderLayout());
        this.controller = controller;
        setBackground(ThemeManager.current().background());
        conversation = new ConversationPanel(controller);
        controller.setCallListener(() -> CallDialog.update(this, controller));
        chatList = new ChatListPanel(controller, id -> {
            conversation.openConversation(id);
            openConversation.accept(id);
        });
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, chatList, conversation);
        split.setDividerLocation(310);
        split.setResizeWeight(0.28);
        split.setBorder(null);
        content.add(split, "CHATS");
        settings = new SettingsPanel(themeChanged, readReceipts, simulateNetworkDrop, controller.callSettings());
        content.add(settings, "SETTINGS");
        campusPulse = new CampusPulsePanel(controller);
        content.add(campusPulse, "PULSE");
        profile = new ProfilePanel(controller);
        content.add(profile, "PROFILE");
        adminConsole = new AdminConsolePanel(controller);
        content.add(adminConsole, "ADMIN");
        rail.setBorder(new EmptyBorder(8, 6, 8, 6));
        rail.setBackground(ThemeManager.current().surface());
        rail.setLayout(new javax.swing.BoxLayout(rail, javax.swing.BoxLayout.Y_AXIS));
        JButton chats = new JButton("Chats");
        JButton settings = new JButton("Settings");
        JButton pulse = new JButton("Campus Pulse");
        JButton profile = new JButton("Profile");
        admin.setVisible(false);
        chats.addActionListener(event -> pages.show(content, "CHATS"));
        settings.addActionListener(event -> pages.show(content, "SETTINGS"));
        pulse.addActionListener(event -> {
            pages.show(content, "PULSE");
            controller.requestFeed(null);
        });
        profile.addActionListener(event -> pages.show(content, "PROFILE"));
        admin.addActionListener(event -> {
            pages.show(content, "ADMIN");
            controller.requestAdminData();
        });
        for (JButton button : java.util.List.of(chats, pulse, profile, settings, admin)) {
            button.setMaximumSize(new java.awt.Dimension(140, 44));
            button.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        }
        rail.add(chats);
        rail.add(pulse);
        rail.add(profile);
        rail.add(settings);
        rail.add(admin);
        add(rail, BorderLayout.WEST);
        add(content, BorderLayout.CENTER);
        add(toast, BorderLayout.SOUTH);
        setMinimumSize(new java.awt.Dimension(960, 640));
        setPreferredSize(new java.awt.Dimension(1200, 780));
        ThemeManager.applyToTree(this);
        controller.addListener(this);
    }

    @Override public void onModelChanged(ClientStateModel model, String eventType, Message message) {
        if (eventType.equals("AUTH_OK")) { admin.setVisible(model.administrator()); }
    }

    public ChatListPanel chatList() { return chatList; }
    public ConversationPanel conversation() { return conversation; }
    public ToastBanner toast() { return toast; }

    public void applyTheme() {
        SwingUtilities.updateComponentTreeUI(this);
        ThemeManager.applyToTree(this);
        setBackground(ThemeManager.current().background());
        rail.setBackground(ThemeManager.current().surface());
        chatList.applyTheme();
        conversation.applyTheme();
        settings.applyTheme();
        campusPulse.applyTheme();
        profile.applyTheme();
        adminConsole.setBackground(ThemeManager.current().background());
        toast.setBackground(ThemeManager.current().elevated());
        revalidate();
        repaint();
    }
}
