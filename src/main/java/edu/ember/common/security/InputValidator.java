package edu.ember.common.security;

import edu.ember.common.exceptions.ValidationException;
import java.util.regex.Pattern;

/** Applies server-side allowlist and length validation to user input. @concept validation, regular expressions */
public final class InputValidator {
    private static final Pattern USERNAME = Pattern.compile("[a-zA-Z0-9_]{3,20}");

    private InputValidator() {
        throw new AssertionError("No instances");
    }

    public static String validateUsername(String username) throws ValidationException {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new ValidationException("Username must be 3–20 letters, digits, or underscores");
        }
        return username;
    }

    public static char[] validatePassword(char[] password) throws ValidationException {
        if (password == null || password.length < 8 || password.length > 128) {
            throw new ValidationException("Password must be 8–128 characters");
        }
        return password.clone();
    }

    public static String validateMessageBody(String body) throws ValidationException {
        if (body == null) {
            throw new ValidationException("Message body is required");
        }
        StringBuilder clean = new StringBuilder(body.length());
        for (int index = 0; index < body.length(); index++) {
            char character = body.charAt(index);
            // Newlines and tabs are kept so multi-line messages survive; other controls are dropped.
            if (!Character.isISOControl(character) || character == '\n' || character == '\t') {
                clean.append(character);
            }
        }
        if (clean.length() < 1) {
            throw new ValidationException("Message body is required");
        }
        return clean.toString();
    }
}
