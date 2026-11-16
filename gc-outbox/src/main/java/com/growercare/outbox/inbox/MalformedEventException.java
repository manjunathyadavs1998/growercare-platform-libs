package com.growercare.outbox.inbox;

/**
 * SPEC-PLAT-04 R60: a record that can never be processed (not JSON, no event id/type, a required field missing).
 * Consumers classify it as <b>not retryable</b>, so it goes to the DLQ at once.
 */
public class MalformedEventException extends RuntimeException {

    public MalformedEventException(String message) {
        super(message);
    }

    public MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
