package edu.ember.common.exceptions;

/** Hides whether an inaccessible social object exists. @concept checked exceptions */
public final class ResourceNotFoundException extends EmberException {
    private static final long serialVersionUID = 1L;

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
