package edu.ember.server;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The public encryption keys of one user's devices, for end-to-end encrypted chats. The server
 * only stores and hands out these public keys; the private halves never leave the devices.
 * A logged-out device's key is kept as retired, so its old messages can still be checked,
 * but new messages are no longer encrypted for it.
 * @concept records, immutable lists, validation
 */
public final class DeviceKeys {
    /** At most this many keys per user; the oldest retired ones go first. */
    static final int MAX_KEYS = 24;

    /** One device: an id, its P-256 public key (65-byte uncompressed point, base64url), and state. */
    public record Key(String deviceId, String publicKey, long addedMs, boolean active) { }

    private DeviceKeys() {
    }

    public static boolean validDeviceId(String deviceId) {
        return deviceId != null && deviceId.matches("[A-Za-z0-9_-]{16,43}");
    }

    /** True for a base64url P-256 public key in uncompressed form (0x04 || X || Y). */
    public static boolean validPublicKey(String publicKey) {
        try {
            byte[] raw = Base64.getUrlDecoder().decode(publicKey);
            return raw.length == 65 && raw[0] == 0x04;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public static List<Key> parse(String stored) {
        List<Key> keys = new ArrayList<>();
        if (stored == null || stored.isEmpty()) {
            return keys;
        }
        for (String line : stored.split("\n")) {
            String[] parts = line.split("\\|");
            if (parts.length == 4) {
                keys.add(new Key(parts[0], parts[1], Long.parseLong(parts[2]), parts[3].equals("1")));
            }
        }
        return keys;
    }

    public static String format(List<Key> keys) {
        StringBuilder text = new StringBuilder();
        for (Key key : keys) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(key.deviceId()).append('|').append(key.publicKey()).append('|')
                    .append(key.addedMs()).append('|').append(key.active() ? '1' : '0');
        }
        return text.toString();
    }

    /**
     * Adds or re-activates a device. A device id keeps the key it was first registered with,
     * so nobody can silently swap the key behind an existing device. Returns null when refused.
     */
    public static List<Key> add(List<Key> keys, String deviceId, String publicKey, long now) {
        List<Key> next = new ArrayList<>();
        boolean found = false;
        for (Key key : keys) {
            if (key.deviceId().equals(deviceId)) {
                if (!key.publicKey().equals(publicKey)) {
                    return null;
                }
                next.add(new Key(deviceId, publicKey, key.addedMs(), true));
                found = true;
            } else {
                next.add(key);
            }
        }
        if (!found) {
            next.add(new Key(deviceId, publicKey, now, true));
        }
        while (next.size() > MAX_KEYS) {
            Key oldest = next.stream().filter(key -> !key.active()).findFirst().orElse(next.get(0));
            next.remove(oldest);
        }
        return next;
    }

    /** Marks a device as logged out: kept for checking old messages, never encrypted to again. */
    public static List<Key> retire(List<Key> keys, String deviceId) {
        List<Key> next = new ArrayList<>();
        for (Key key : keys) {
            next.add(key.deviceId().equals(deviceId)
                    ? new Key(key.deviceId(), key.publicKey(), key.addedMs(), false) : key);
        }
        return next;
    }
}
