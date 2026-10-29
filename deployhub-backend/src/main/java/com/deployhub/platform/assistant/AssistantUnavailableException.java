package com.deployhub.platform.assistant;

/** The AI can't answer right now (no API key, network, rate limit, outage). Callers use the built-in replies. */
public class AssistantUnavailableException extends RuntimeException {

    public AssistantUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public AssistantUnavailableException(String message) {
        super(message);
    }
}
