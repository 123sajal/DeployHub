package com.deployhub.platform.models.enums;

/** Tells the UI and API clients whether a deployment really ran anything. */
public enum ExecutionMode {
    /** The built-in simulator: nothing is cloned, built or run. */
    SIMULATED,
    /** Cloned, built with Docker and started in a container on this machine (Milestone 6). */
    REAL
}
