package com.deployhub.platform.models.enums;

public enum JobStatus {
    /** Waiting to be claimed (new, or scheduled for a retry at available_at). */
    PENDING,
    /** Claimed by a worker holding an unexpired lease. */
    RUNNING,
    SUCCEEDED,
    /** Failed with a permanent error; never retried. */
    FAILED,
    /** Retries exhausted ("dead letter"); kept for inspection. */
    DEAD,
    CANCELLED;

    public boolean isFinished() {
        return this == SUCCEEDED || this == FAILED || this == DEAD || this == CANCELLED;
    }
}
