package edu.ember.common.exceptions;

/** Reports that authentication is temporarily locked. @concept exception specialization */
public final class AuthenticationLockedException extends AuthenticationException {
    private static final long serialVersionUID = 1L;

    public AuthenticationLockedException(String message) {
        super(message);
    }
}
