package edu.ember.client.call;

/** The two sides of a call share no voice codec. @concept exception specialization */
public final class CodecNegotiationException extends CallException {
    private static final long serialVersionUID = 1L;

    public CodecNegotiationException(String message) {
        super(message);
    }
}
