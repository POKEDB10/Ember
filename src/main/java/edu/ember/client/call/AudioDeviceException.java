package edu.ember.client.call;

/** A microphone or speaker could not be opened, or stopped working (for example it was unplugged). @concept exception specialization */
public final class AudioDeviceException extends CallException {
    private static final long serialVersionUID = 1L;

    public AudioDeviceException(String message) {
        super(message);
    }

    public AudioDeviceException(String message, Throwable cause) {
        super(message, cause);
    }
}
