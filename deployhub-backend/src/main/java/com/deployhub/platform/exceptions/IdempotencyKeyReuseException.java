package com.deployhub.platform.exceptions;

/** The same Idempotency-Key was sent again, but with a different request body/target. */
public class IdempotencyKeyReuseException extends RuntimeException {

    public IdempotencyKeyReuseException() {
        super("This Idempotency-Key was already used for a different request. Use a new key for a new deployment.");
    }
}
