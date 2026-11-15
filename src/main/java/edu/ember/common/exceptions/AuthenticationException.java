package edu.ember.common.exceptions;

/** Reports a failed authentication operation without exposing account existence. @concept checked exceptions */
public class AuthenticationException extends EmberException {
    private static final long serialVersionUID = 1L;

    public AuthenticationException(String message) {
        super(message);
    }
}
