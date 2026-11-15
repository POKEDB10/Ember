package edu.ember.client;

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
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

/** Swing entry point for the minimal login and direct chat client. @concept Swing, MVC, listeners */
public final class EmberClientApp {
    private final JFrame frame = new JFrame(AppInfo.NAME);
    private final CardLayout cards = new CardLayout();
    private final JPanel root = new JPanel(cards);
    private final JTextField username = new JTextField(18);
    private final JPasswordField password = new JPasswordField(18);
    private final JTextField recipient = new JTextField("bob", 14);
    private final JTextArea transcript = new JTextArea();
    private final JTextField composer = new JTextField();
    private final JLabel status = new JLabel("Connecting to 127.0.0.1…");
    private final String host;
    private final int port;
    private final Path trustStore;
    private final char[] storePassword;
    private ClientController controller;

    private EmberClientApp(String host, int port, String profile, Path trustStore,
            char[] storePassword) {
        this.host = host;
        this.port = port;
        this.trustStore = trustStore;
        this.storePassword = storePassword;
        username.setText(profile);
        buildWindow();
        connect();
    }

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        String profile = "";
        int port = 7443;
        for (int index = 0; index + 1 < args.length; index++) {
            if (args[index].equals("--host")) {
                host = args[++index];
            } else if (args[index].equals("--port")) {
                port = Integer.parseInt(args[++index]);
            } else if (args[index].equals("--profile")) {
                profile = args[++index];
            }
        }
        Path config = Path.of("config");
        char[] password = readStorePassword(config.resolve("secrets.properties"));
        String targetHost = host;
        String targetProfile = profile;
        int targetPort = port;
        SwingUtilities.invokeLater(() -> new EmberClientApp(targetHost, targetPort, targetProfile,
                config.resolve("ember-client-trust.p12"), password));
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
        frame.setMinimumSize(new Dimension(640, 480));
        frame.setSize(840, 620);
        frame.setLocationRelativeTo(null);
        root.add(createLoginPanel(), "AUTH");
        root.add(createChatPanel(), "CHAT");
        frame.setContentPane(root);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                if (controller != null) {
                    controller.close();
                }
                Arrays.fill(storePassword, '\0');
            }
        });
        frame.setVisible(true);
    }

    private JPanel createLoginPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(48, 90, 48, 90));
        JPanel fields = new JPanel(new java.awt.GridLayout(0, 1, 6, 6));
        fields.add(new JLabel(AppInfo.NAME + " — " + AppInfo.TAGLINE));
        fields.add(new JLabel("Username"));
        fields.add(username);
        fields.add(new JLabel("Password"));
        fields.add(password);
        JButton login = new JButton("Log in");
        JButton register = new JButton("Register");
        JPanel actions = new JPanel();
        actions.add(login);
        actions.add(register);
        fields.add(actions);
        fields.add(status);
        login.addActionListener(event -> authenticate(false));
        register.addActionListener(event -> authenticate(true));
        panel.add(fields, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createChatPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        JPanel header = new JPanel(new BorderLayout(8, 8));
        header.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        header.add(new JLabel("Conversation with"), BorderLayout.WEST);
        header.add(recipient, BorderLayout.CENTER);
        panel.add(header, BorderLayout.NORTH);
        transcript.setEditable(false);
        transcript.setLineWrap(true);
        transcript.setWrapStyleWord(true);
        panel.add(new JScrollPane(transcript), BorderLayout.CENTER);
        composer.addActionListener(event -> sendMessage());
        panel.add(composer, BorderLayout.SOUTH);
        return panel;
    }

    private void connect() {
        status.setText("Connecting to " + host + ":" + port + "…");
        new SwingWorker<ClientController, Void>() {
            @Override
            protected ClientController doInBackground() throws Exception {
                return new ClientController(host, port, trustStore, storePassword);
            }

            @Override
            protected void done() {
                try {
                    controller = get();
                    controller.addListener(EmberClientApp.this::modelChanged);
                    status.setText("Connected with TLS 1.3");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    status.setText("Connection cancelled");
                } catch (ExecutionException exception) {
                    status.setText("Connection failed: " + exception.getCause().getMessage());
                }
            }
        }.execute();
    }

    private void authenticate(boolean register) {
        if (controller == null) {
            status.setText("Wait for the server connection");
            return;
        }
        String name = username.getText().trim();
        char[] secret = password.getPassword();
        if (register) {
            String displayName = JOptionPane.showInputDialog(frame, "Display name");
            if (displayName == null) {
                Arrays.fill(secret, '\0');
                return;
            }
            controller.register(name, secret, displayName);
        } else {
            controller.login(name, secret);
        }
        Arrays.fill(secret, '\0');
        status.setText("Verifying…");
    }

    private void sendMessage() {
        String target = recipient.getText().trim().toLowerCase(java.util.Locale.ROOT);
        String sender = controller.model().userId();
        String conversationId = sender.compareTo(target) < 0
                ? "dm:" + sender + ":" + target : "dm:" + target + ":" + sender;
        String body = composer.getText();
        if (!body.isBlank()) {
            controller.sendMessage(conversationId, body);
            composer.setText("");
        }
    }

    private void modelChanged(ClientStateModel model, String eventType, Message message) {
        if (eventType.equals("AUTH_OK")) {
            cards.show(root, "CHAT");
            frame.setTitle(AppInfo.NAME + " — " + model.userId());
        } else if (eventType.equals("MESSAGE") && message != null) {
            transcript.append(message.senderUserId() + ": " + message.body() + "\n");
        } else if (eventType.equals("MESSAGE_STATUS") && message != null) {
            if (message.senderUserId().equals(model.userId())) {
                status.setText(message.status().name());
            }
        } else if (eventType.equals("ERROR")) {
            status.setText(model.errorMessage());
        }
    }
}
