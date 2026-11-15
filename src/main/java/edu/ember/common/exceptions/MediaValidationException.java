package edu.ember.common.exceptions;

/** Reports invalid uploaded media. @concept checked exceptions */
public final class MediaValidationException extends EmberException {
    private static final long serialVersionUID = 1L;

    public MediaValidationException(String message) {
        super(message);
    }
}
