package edu.ember.common.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses JSON into Map, List, String, Double, Boolean and null. Enough for reading REST
 * responses without a third-party library. @concept recursion, collections, exceptions
 */
public final class MiniJson {
    private final String text;
    private int position;

    private MiniJson(String text) {
        this.text = text;
    }

    public static Object parse(String text) {
        MiniJson parser = new MiniJson(text);
        Object value = parser.value();
        parser.skipSpace();
        if (parser.position != text.length()) {
            throw parser.error("Unexpected trailing characters");
        }
        return value;
    }

    /** Writes a JSON string literal, escaping quotes, backslashes and control characters. */
    public static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (char character : value.toCharArray()) {
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (character < 0x20) {
                        out.append(String.format("\\u%04x", (int) character));
                    } else {
                        out.append(character);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** Follows a path of object keys, e.g. path(json, "data", "id"); null when any step is missing. */
    public static Object path(Object node, String... keys) {
        for (String key : keys) {
            if (!(node instanceof Map<?, ?> map)) {
                return null;
            }
            node = map.get(key);
        }
        return node;
    }

    private Object value() {
        skipSpace();
        if (position >= text.length()) {
            throw error("Unexpected end of JSON");
        }
        char next = text.charAt(position);
        if (next == '{') {
            return object();
        } else if (next == '[') {
            return array();
        } else if (next == '"') {
            return string();
        } else if (text.startsWith("true", position)) {
            position += 4;
            return Boolean.TRUE;
        } else if (text.startsWith("false", position)) {
            position += 5;
            return Boolean.FALSE;
        } else if (text.startsWith("null", position)) {
            position += 4;
            return null;
        }
        return number();
    }

    private Map<String, Object> object() {
        Map<String, Object> result = new LinkedHashMap<>();
        position++;
        skipSpace();
        if (peek('}')) {
            return result;
        }
        do {
            skipSpace();
            String key = string();
            skipSpace();
            expect(':');
            result.put(key, value());
            skipSpace();
        } while (consume(','));
        expect('}');
        return result;
    }

    private List<Object> array() {
        List<Object> result = new ArrayList<>();
        position++;
        skipSpace();
        if (peek(']')) {
            return result;
        }
        do {
            result.add(value());
            skipSpace();
        } while (consume(','));
        expect(']');
        return result;
    }

    private String string() {
        expect('"');
        StringBuilder builder = new StringBuilder();
        while (position < text.length()) {
            char character = text.charAt(position++);
            if (character == '"') {
                return builder.toString();
            }
            if (character != '\\') {
                builder.append(character);
                continue;
            }
            if (position >= text.length()) {
                break;
            }
            char escaped = text.charAt(position++);
            switch (escaped) {
                case 'n' -> builder.append('\n');
                case 't' -> builder.append('\t');
                case 'r' -> builder.append('\r');
                case 'b' -> builder.append('\b');
                case 'f' -> builder.append('\f');
                case 'u' -> {
                    if (position + 4 > text.length()) {
                        throw error("Bad unicode escape");
                    }
                    builder.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                    position += 4;
                }
                default -> builder.append(escaped);
            }
        }
        throw error("Unterminated string");
    }

    private Double number() {
        int start = position;
        while (position < text.length() && "+-0123456789.eE".indexOf(text.charAt(position)) >= 0) {
            position++;
        }
        try {
            return Double.valueOf(text.substring(start, position));
        } catch (NumberFormatException exception) {
            throw error("Bad value");
        }
    }

    private void skipSpace() {
        while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
            position++;
        }
    }

    private boolean peek(char expected) {
        if (position < text.length() && text.charAt(position) == expected) {
            position++;
            return true;
        }
        return false;
    }

    private boolean consume(char expected) {
        return peek(expected);
    }

    private void expect(char expected) {
        if (!peek(expected)) {
            throw error("Expected '" + expected + "'");
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at position " + position);
    }
}
