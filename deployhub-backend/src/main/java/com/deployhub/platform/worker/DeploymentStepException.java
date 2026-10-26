package com.deployhub.platform.worker;

import lombok.Getter;

/**
 * A deployment step failed. {@code retryable} decides what happens next: transient problems
 * (timeouts, unavailable registry) are retried with backoff; permanent ones (a broken build
 * command) fail the deployment at once, because retrying would just fail again.
 */
@Getter
public class DeploymentStepException extends RuntimeException {

    private final boolean retryable;

    private DeploymentStepException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public static DeploymentStepException transientError(String message) {
        return new DeploymentStepException(message, true);
    }

    public static DeploymentStepException permanent(String message) {
        return new DeploymentStepException(message, false);
    }
}
