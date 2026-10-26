package com.deployhub.platform.exceptions;

import lombok.Getter;

import java.time.Duration;

@Getter
public class TooManyRequestsException extends RuntimeException {

    private final Duration retryAfter;

    public TooManyRequestsException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }
}
