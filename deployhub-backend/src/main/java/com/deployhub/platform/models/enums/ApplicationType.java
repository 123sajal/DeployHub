package com.deployhub.platform.models.enums;

/** What kind of app a project is. Decides the base image and the default port of a real build. */
public enum ApplicationType {
    JAVA_SPRING_BOOT(8080),
    NODE_JS(3000),
    STATIC_SITE(3000),
    PYTHON(8000),
    /** Built from the repository's own Dockerfile. */
    DOCKERFILE(8080);

    private final int defaultPort;

    ApplicationType(int defaultPort) {
        this.defaultPort = defaultPort;
    }

    /** The port the app is expected to listen on when the project doesn't set one. */
    public int defaultPort() {
        return defaultPort;
    }
}
