package edu.ember.client.call;

import edu.ember.common.exceptions.EmberException;

/**
 * Checked root for call failures the app can explain to the user (no device, no shared codec).
 * Callers must handle it: a call problem must never crash the window or leak an open device.
 * @concept checked exceptions, user-defined exception hierarchy
 */
public class CallException extends EmberException {
    private static final long serialVersionUID = 1L;

    public CallException(String message) {
        super(message);
    }

    public CallException(String message, Throwable cause) {
        super(message, cause);
    }
}
