package edu.ember.common.exceptions;

/** Reports an operation that the current identity is not allowed to perform. @concept checked exceptions */
public final class AuthorizationException extends EmberException {
    private static final long serialVersionUID = 1L;

    public AuthorizationException(String message) {
        super(message);
    }
}
