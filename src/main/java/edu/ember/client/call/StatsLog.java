package edu.ember.client.call;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Writes call stats to a small rotating log file (for testing, report screenshots and the demo).
 * When the file passes 256 KB it becomes {@code .1} and a new file starts, so it never grows
 * without limit. It records numbers only: no names, no message content.
 * @concept file handling, Observer pattern (a listener), encapsulation
 */
public final class StatsLog implements CallStatsListener {
    private static final long MAX_BYTES = 256 * 1024;
    private final Path file;

    public StatsLog(Path file) {
        this.file = file;
    }

    @Override
    public synchronized void statsChanged(CallStats stats) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            if (Files.exists(file) && Files.size(file) > MAX_BYTES) {
                Files.move(file, file.resolveSibling(file.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(file, java.time.Instant.now() + " " + stats.line() + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // A full disk must never disturb the call itself.
        }
    }
}
