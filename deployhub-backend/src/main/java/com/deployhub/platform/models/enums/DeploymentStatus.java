package com.deployhub.platform.models.enums;

public enum DeploymentStatus {
    QUEUED,
    BUILDING,
    STARTING,
    LIVE,
    /** Was LIVE, replaced by a newer LIVE deployment of the same project. */
    SUPERSEDED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == FAILED || this == CANCELLED || this == SUPERSEDED;
    }

    public boolean isInProgress() {
        return this == QUEUED || this == BUILDING || this == STARTING;
    }
}
