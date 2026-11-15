package edu.ember.common.exceptions;

/** Reports user input that does not meet an allowlist or length rule. @concept checked exceptions */
public final class ValidationException extends EmberException {
    private static final long serialVersionUID = 1L;

    public ValidationException(String message) {
        super(message);
    }
}
