package edu.ember.common.exceptions;

/** Reports that an operation exceeded its rate limit. @concept checked exceptions */
public final class RateLimitedException extends EmberException {
    private static final long serialVersionUID = 1L;

    public RateLimitedException(String message) {
        super(message);
    }
}
