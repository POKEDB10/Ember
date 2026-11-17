package edu.ember.client;

import edu.ember.client.ui.AuthPanel;
import edu.ember.client.ui.ChatWorkspacePanel;
import edu.ember.client.ui.NotificationService;
import edu.ember.client.ui.ThemeManager;
import edu.ember.common.model.Message;
import edu.ember.common.util.AppInfo;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;

/** Starts the themed Swing client and coordinates login, chat, and reconnect. @concept Swing, MVC, listeners */
public final class EmberClientApp {
    private final JFrame frame = new JFrame(AppInfo.NAME);
    private final CardLayout cards = new CardLayout();
    private final JPanel root = new JPanel(cards);
    private final JLabel status = new JLabel("Connecting…");
    private final String host;
    private final int port;
    /** Website address (e.g. https://ember.pokedb.site); null means a direct TLS connection. */
    private final String webAddress;
    private final Path trustStore;
    private final char[] storePassword;
    private final AuthPanel authPanel;
    private ClientController controller;
    private ChatWorkspacePanel workspace;
    private ReconnectManager reconnectManager;

    private EmberClientApp(String host, int port, String webAddress, String profile,
            Path trustStore, char[] storePassword) {
        this.webAddress = webAddress;
        this.host = host;
        this.port = port;
        this.trustStore = trustStore;
        this.storePassword = storePassword;
        authPanel = new AuthPanel(new AuthPanel.AuthActions() {
            @Override
            public void login(String username, char[] password) {
                authenticate(false, username, password);
            }

            @Override
            public void register(String username, char[] password) {
                String displayName = JOptionPane.showInputDialog(frame, "Display name");
                if (displayName != null) {
                    authenticate(true, username, password, displayName);
                }
            }
        });
        if (!profile.isBlank()) {
            authPanel.setUsername(profile);
        }
        buildWindow();
        connect();
    }

    public static void main(String[] args) throws Exception {
        UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
        ThemeManager.apply(ThemeManager.Theme.DARK);
        String host = "127.0.0.1";
        String profile = "";
        String webAddress = null;
        int port = 7443;
        for (int index = 0; index + 1 < args.length; index++) {
            if (args[index].equals("--url")) {
                webAddress = args[++index];
            } else if (args[index].equals("--host")) {
                host = args[++index];
            } else if (args[index].equals("--port")) {
                port = Integer.parseInt(args[++index]);
            } else if (args[index].equals("--profile")) {
                profile = args[++index];
            }
        }
        Path config = Path.of("config");
        // A website address uses the system's trusted certificates, so no local truststore is needed.
        char[] password = webAddress != null ? new char[0]
                : readStorePassword(config.resolve("secrets.properties"));
        String targetHost = host;
        String targetProfile = profile;
        String targetWeb = webAddress;
        int targetPort = port;
        SwingUtilities.invokeLater(() -> new EmberClientApp(targetHost, targetPort, targetWeb,
                targetProfile, config.resolve("ember-client-trust.p12"), password));
    }

    private static char[] readStorePassword(Path path) throws Exception {
        for (String line : Files.readAllLines(path, java.nio.charset.StandardCharsets.UTF_8)) {
            if (line.startsWith("EMBER_KEYSTORE_PASS=")) {
                return line.substring("EMBER_KEYSTORE_PASS=".length()).toCharArray();
            }
        }
        throw new IllegalStateException("Keystore password missing from config/secrets.properties");
    }

    private void buildWindow() {
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setMinimumSize(new Dimension(960, 640));
        frame.setSize(1200, 780);
        frame.setLocationRelativeTo(null);
        root.add(authPanel, "AUTH");
        frame.add(root, BorderLayout.CENTER);
        status.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 12, 6, 12));
        frame.add(status, BorderLayout.SOUTH);
        bindShortcuts();
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                if (reconnectManager != null) {
                    reconnectManager.cancelReconnect();
                }
                if (controller != null) {
                    controller.close();
                }
                Arrays.fill(storePassword, '\0');
            }
        });
        frame.setVisible(true);
    }

    private void connect() {
        status.setText("Connecting to " + (webAddress != null ? webAddress : host + ":" + port) + "…");
        new SwingWorker<ClientController, Void>() {
            @Override
            protected ClientController doInBackground() throws Exception {
                return newController();
            }

            @Override
            protected void done() {
                try {
                    attachController(get(), null);
                    status.setText("Connected · " + controller.transport());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    status.setText("Connection cancelled");
                } catch (ExecutionException exception) {
                    status.setText("Connection failed: " + exception.getCause().getMessage());
                    startReconnect(null);
                }
            }
        }.execute();
    }

    private ClientController newController() throws Exception {
        return webAddress != null
                ? new ClientController(ServerLink.webSocket(webAddress), Path.of("client_data"))
                : new ClientController(host, port, trustStore, storePassword);
    }

    private void attachController(ClientController replacement, String conversationId) {
        controller = replacement;
        controller.addListener(this::modelChanged);
        if (workspace != null) {
            root.remove(workspace);
        }
        workspace = new ChatWorkspacePanel(controller, this::applyTheme,
                controller::setReadReceiptsEnabled, controller::simulateNetworkDrop, ignored -> { });
        root.add(workspace, "MAIN");
        if (conversationId != null) {
            workspace.conversation().openConversation(conversationId);
        }
        root.revalidate();
        root.repaint();
    }

    private void authenticate(boolean register, String username, char[] secret) {
        if (register) {
            String displayName = JOptionPane.showInputDialog(frame, "Display name");
            if (displayName != null) {
                authenticate(true, username, secret, displayName);
            }
        } else {
            authenticate(false, username, secret, "");
        }
    }

    private void authenticate(boolean register, String username, char[] secret, String displayName) {
        if (controller == null) {
            status.setText("Wait for the server connection");
            return;
        }
        if (register) {
            controller.register(username, secret, displayName);
        } else {
            controller.login(username, secret);
        }
        status.setText("Verifying…");
    }

    private void modelChanged(ClientStateModel model, String eventType, Message message) {
        if (eventType.equals("AUTH_OK")) {
            cards.show(root, "MAIN");
            frame.setTitle(AppInfo.NAME + " — " + model.userId());
            status.setText("Connected · " + controller.transport());
            bindShortcuts();
        } else if (eventType.equals("MESSAGE") && message != null) {
            if (!frame.isFocused() && workspace != null) {
                NotificationService.show(frame, workspace.toast(), message.senderUserId(), message.body());
            }
        } else if (eventType.equals("MESSAGE_STATUS") && message != null) {
            if (message.senderUserId().equals(model.userId())) {
                status.setText(message.status().name());
            }
        } else if (eventType.equals("ERROR")) {
            status.setText(model.errorMessage());
            authPanel.setError(model.errorMessage());
            if ("503".equals(model.errorCode()) && reconnectManager == null) {
                startReconnect(model.userId());
            }
        }
    }

    private void startReconnect(String userId) {
        if (reconnectManager != null) {
            return;
        }
        status.setText("Connection lost · reconnecting…");
        reconnectManager = new ReconnectManager(
                this::newController,
                new ReconnectManager.ReconnectListener() {
                    @Override
                    public void onReconnected(ClientController replacement) {
                        SwingUtilities.invokeLater(() -> {
                            String active = controller == null ? null
                                    : controller.model().activeConversationId();
                            if (controller != null) {
                                controller.close();
                            }
                            attachController(replacement, active);
                            if (userId != null) {
                                try {
                                    replacement.resumeStoredSession(userId);
                                } catch (java.io.IOException exception) {
                                    status.setText("Reconnect session unavailable: "
                                            + exception.getMessage());
                                }
                            }
                            reconnectManager = null;
                            status.setText("Reconnected · syncing messages…");
                        });
                    }

                    @Override
                    public void onAttemptFailed(Exception exception) {
                        SwingUtilities.invokeLater(() ->
                                status.setText("Reconnecting: " + exception.getMessage()));
                    }
                });
        reconnectManager.start();
    }

    private void bindShortcuts() {
        InputMap input = root.getInputMap(JPanel.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ActionMap actions = root.getActionMap();
        input.put(KeyStroke.getKeyStroke("control D"), "toggle-theme");
        actions.put("toggle-theme", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                ThemeManager.apply(ThemeManager.current() == ThemeManager.Theme.DARK
                        ? ThemeManager.Theme.LIGHT : ThemeManager.Theme.DARK);
                applyTheme();
            }
        });
        input.put(KeyStroke.getKeyStroke("control F"), "focus-search");
        actions.put("focus-search", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (workspace != null) {
                    workspace.chatList().searchField().requestFocusInWindow();
                }
            }
        });
    }

    private void applyTheme() {
        if (workspace != null) {
            workspace.applyTheme();
        }
        authPanel.applyTheme();
        frame.repaint();
    }
}
