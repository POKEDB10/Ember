package edu.ember.common.exceptions;

/** Checked root for errors the application can report safely. @concept inheritance, exceptions */
public class EmberException extends Exception {
    private static final long serialVersionUID = 1L;

    public EmberException(String message) {
        super(message);
    }

    public EmberException(String message, Throwable cause) {
        super(message, cause);
    }
}
