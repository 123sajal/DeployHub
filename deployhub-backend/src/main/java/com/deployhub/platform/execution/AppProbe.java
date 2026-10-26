package com.deployhub.platform.execution;

/** Asks a running app whether it is up. */
@FunctionalInterface
public interface AppProbe {

    /** The HTTP status the app answers with on {@code 127.0.0.1:hostPort/path}, or -1 if nothing answers yet. */
    int status(int hostPort, String path);
}
