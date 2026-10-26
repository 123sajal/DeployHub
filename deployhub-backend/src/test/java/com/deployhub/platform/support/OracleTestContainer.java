package com.deployhub.platform.support;

import org.testcontainers.oracle.OracleContainer;

/**
 * One real Oracle Database 23ai Free container shared by every oracle-tagged test in the JVM
 * (starting Oracle takes ~20-40 s, so we only do it once). Requires Docker.
 */
public final class OracleTestContainer {

    private static final OracleContainer CONTAINER = new OracleContainer("gvenzl/oracle-free:23-slim-faststart");

    private OracleTestContainer() {
    }

    public static synchronized OracleContainer get() {
        if (!CONTAINER.isRunning()) {
            CONTAINER.start();
        }
        return CONTAINER;
    }
}
