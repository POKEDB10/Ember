package edu.ember.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Appends one escaped JSON object per security event without credential material. @concept file I/O, escaping */
public final class SecurityAuditLogger {
    private final Path logPath;

    public SecurityAuditLogger(Path logPath) {
        this.logPath = logPath;
    }

    public synchronized void log(String event, String username, String source) throws IOException {
        Path parent = logPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String json = "{\"ts\":" + System.currentTimeMillis()
                + ",\"event\":\"" + escape(event) + "\",\"username\":\""
                + escape(username) + "\",\"source\":\"" + escape(source) + "\"}\n";
        Files.writeString(logPath, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\') {
                escaped.append('\\').append(character);
            } else if (Character.isISOControl(character)) {
                escaped.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) character));
            } else {
                escaped.append(character);
            }
        }
        return escaped.toString();
    }
}
