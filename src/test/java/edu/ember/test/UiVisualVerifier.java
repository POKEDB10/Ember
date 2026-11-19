package edu.ember.test;

import edu.ember.client.ClientController;
import edu.ember.client.ClientStateModel;
import edu.ember.client.ui.ChatWorkspacePanel;
import edu.ember.client.ui.ThemeManager;
import edu.ember.common.model.Message;
import edu.ember.server.EmberServer;
import edu.ember.server.SecurityAuditLogger;
import edu.ember.server.storage.InMemoryStorageEngine;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Captures and inspects the real chat workspace offscreen at both target sizes. @concept Swing EDT, AWT painting */
public final class UiVisualVerifier {
    private UiVisualVerifier() {
    }

    public static void main(String[] args) throws Exception {
        UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
        ThemeManager.apply(ThemeManager.Theme.DARK);
        Path config = Path.of("config");
        char[] password = readStorePassword(config.resolve("secrets.properties"));
        Path dataRoot = Path.of("build", "test-data", "ui-clients");
        deleteTree(dataRoot);
        EmberServer server = new EmberServer(config.resolve("ember-server.p12"), password, 0,
                new InMemoryStorageEngine(), new SecurityAuditLogger(
                        Path.of("build", "test-data", "ui-audit.json")));
        ClientController alice = null;
        ClientController bob = null;
        try {
            server.start();
            alice = new ClientController("127.0.0.1", server.port(),
                    config.resolve("ember-client-trust.p12"), password, dataRoot);
            bob = new ClientController("127.0.0.1", server.port(),
                    config.resolve("ember-client-trust.p12"), password, dataRoot);
            exerciseScreenshots(alice, bob);
            System.out.println("Phase 5 visual checks passed");
        } finally {
            if (alice != null) alice.close();
            if (bob != null) bob.close();
            server.close();
            Arrays.fill(password, '\0');
            deleteTree(dataRoot);
        }
    }

    private static void exerciseScreenshots(ClientController alice, ClientController bob)
            throws Exception {
        CountDownLatch authenticated = new CountDownLatch(2);
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<Throwable> uiFailure = new AtomicReference<>();
        String longText = "مرحبا يا أصدقاء 🌋🌋 — Ember sends long messages with Arabic script and emoji. "
                .repeat(5);
        String reply = "وصلت الرسالة، شكراً 🌟 مرحبا";
        alice.addListener((model, event, message) -> {
            if (event.equals("AUTH_OK")) authenticated.countDown();
        });
        bob.addListener((model, event, message) -> {
            if (event.equals("AUTH_OK")) authenticated.countDown();
            if (event.equals("MESSAGE") && message != null && message.body().equals(longText)) {
                bob.sendMessage("dm:alice:bob", reply);
            }
        });
        alice.addListener((model, event, message) -> {
            if (event.equals("MESSAGE") && message != null && message.body().equals(reply)) {
                delivered.countDown();
            }
        });
        alice.register("alice", "alice-pass-42".toCharArray(), "Alice");
        bob.register("bob", "bob-pass-42".toCharArray(), "Bob");
        require(authenticated.await(30, TimeUnit.SECONDS), "Visual clients did not authenticate");
        alice.sendMessage("dm:alice:bob", longText);
        require(delivered.await(30, TimeUnit.SECONDS), "Arabic/emoji visual fixture was not delivered");
        CountDownLatch completed = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                captureThemesAndSizes(alice, uiFailure);
            } catch (Throwable throwable) {
                uiFailure.set(throwable);
            } finally {
                completed.countDown();
            }
        });
        require(completed.await(30, TimeUnit.SECONDS), "Offscreen UI capture timed out");
        if (uiFailure.get() != null) {
            throw new AssertionError("Offscreen UI verification failed", uiFailure.get());
        }
    }

    private static void captureThemesAndSizes(ClientController controller,
            AtomicReference<Throwable> failure) throws Exception {
        Path output = Path.of("docs", "screenshots");
        Files.createDirectories(output);
        int[][] sizes = {{1280, 720}, {1920, 1080}};
        for (ThemeManager.Theme theme : ThemeManager.Theme.values()) {
            ThemeManager.apply(theme);
            for (int[] size : sizes) {
                ChatWorkspacePanel workspace = new ChatWorkspacePanel(controller, () -> { },
                        controller::setReadReceiptsEnabled, controller::simulateNetworkDrop,
                        ignored -> { });
                workspace.conversation().openConversation("dm:alice:bob");
                workspace.setSize(size[0], size[1]);
                layoutTree(workspace);
                require(workspace.chatList().visibleConversationCount() > 0,
                        "Existing conversation did not appear in the chat list");
                assertWrappedMessages(workspace.conversation().transcriptContent());
                BufferedImage image = new BufferedImage(size[0], size[1],
                        BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = image.createGraphics();
                workspace.paint(graphics);
                graphics.dispose();
                String themeName = theme.name().toLowerCase(java.util.Locale.ROOT);
                Path file = output.resolve("phase-5-" + themeName + "-" + size[0] + "x"
                        + size[1] + ".png");
                ImageIO.write(image, "png", file.toFile());
                require(Files.size(file) > 10_000, "Screenshot was unexpectedly empty: " + file);
                require(image.getWidth() == size[0] && image.getHeight() == size[1],
                        "Screenshot dimensions changed");
                System.out.println("Captured " + file + " " + size[0] + "x" + size[1]);
            }
        }
    }

    private static void assertWrappedMessages(Container transcript) {
        java.util.List<JTextArea> textAreas = findTextAreas(transcript);
        require(!textAreas.isEmpty(), "No message text area was rendered");
        for (JTextArea textArea : textAreas) {
            require(textArea.getLineWrap() && textArea.getWrapStyleWord(),
                    "Message body must wrap by words");
            require(textArea.getWidth() > 0 && textArea.getHeight() > 0,
                    "Message body has no visible layout bounds");
            require(textArea.getHeight() >= textArea.getPreferredSize().height,
                    "Message body is clipped vertically");
        }
    }

    private static java.util.List<JTextArea> findTextAreas(Container container) {
        java.util.List<JTextArea> result = new java.util.ArrayList<>();
        for (Component child : container.getComponents()) {
            if (child instanceof JTextArea textArea) {
                result.add(textArea);
            }
            if (child instanceof Container nested) {
                result.addAll(findTextAreas(nested));
            }
        }
        return result;
    }

    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layoutTree(nested);
            }
        }
    }

    private static char[] readStorePassword(Path path) throws Exception {
        for (String line : Files.readAllLines(path, java.nio.charset.StandardCharsets.UTF_8)) {
            if (line.startsWith("EMBER_KEYSTORE_PASS=")) {
                return line.substring("EMBER_KEYSTORE_PASS=".length()).toCharArray();
            }
        }
        throw new IllegalStateException("Keystore password is missing");
    }

    private static void deleteTree(Path root) throws Exception {
        if (Files.exists(root)) {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
