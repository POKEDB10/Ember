package edu.ember.common.protocol;

import edu.ember.common.exceptions.EmberException;

/** Signals a malformed or over-limit wire frame. @concept user-defined exceptions */
public final class ProtocolFrameException extends EmberException {
    private static final long serialVersionUID = 1L;

    public ProtocolFrameException(String message) {
        super(message);
    }
}
