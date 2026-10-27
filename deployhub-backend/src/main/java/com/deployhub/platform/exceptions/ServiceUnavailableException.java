package com.deployhub.platform.exceptions;

/** Something DeployHub needs (e.g. Docker) is not available right now; the client may retry later (503). */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public ServiceUnavailableException(String message) {
        super(message);
    }
}
