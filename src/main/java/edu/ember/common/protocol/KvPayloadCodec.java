package edu.ember.common.protocol;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.regex.Pattern;

/** Encodes and decodes escaped UTF-8 key/value payloads. @concept StringBuffer, StringTokenizer */
public final class KvPayloadCodec {
    private static final Pattern VALID_KEY = Pattern.compile("[a-zA-Z0-9_.]{1,32}");

    private KvPayloadCodec() {
        throw new AssertionError("No instances");
    }

    public static byte[] encode(Map<String, String> fields) {
        StringBuffer payload = new StringBuffer();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            validateKey(field.getKey());
            if (payload.length() > 0) {
                payload.append('\n');
            }
            payload.append(field.getKey()).append('=').append(escape(field.getValue()));
        }
        return payload.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static Map<String, String> decode(byte[] payloadBytes) {
        String payload = new String(payloadBytes, StandardCharsets.UTF_8);
        Map<String, String> fields = new LinkedHashMap<>();
        StringTokenizer lines = new StringTokenizer(payload, "\n");
        while (lines.hasMoreTokens()) {
            String line = lines.nextToken();
            int separator = line.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException("Malformed key/value line");
            }
            String key = line.substring(0, separator);
            validateKey(key);
            if (fields.putIfAbsent(key, unescape(line.substring(separator + 1))) != null) {
                throw new IllegalArgumentException("Duplicate key in payload");
            }
        }
        return Map.copyOf(fields);
    }

    public static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
        return value;
    }

    private static void validateKey(String key) {
        if (key == null || !VALID_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid payload key");
        }
    }

    private static String escape(String value) {
        StringBuffer escaped = new StringBuffer();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\\') {
                escaped.append("\\\\");
            } else if (character == '\n') {
                escaped.append("\\n");
            } else if (character == '\r') {
                escaped.append("\\r");
            } else {
                escaped.append(character);
            }
        }
        return escaped.toString();
    }

    private static String unescape(String value) {
        StringBuilder decoded = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != '\\') {
                decoded.append(character);
            } else {
                if (++index >= value.length()) {
                    throw new IllegalArgumentException("Incomplete escape sequence");
                }
                char escaped = value.charAt(index);
                if (escaped == '\\') {
                    decoded.append('\\');
                } else if (escaped == 'n') {
                    decoded.append('\n');
                } else if (escaped == 'r') {
                    decoded.append('\r');
                } else {
                    throw new IllegalArgumentException("Unknown escape sequence");
                }
            }
        }
        return decoded.toString();
    }
}
