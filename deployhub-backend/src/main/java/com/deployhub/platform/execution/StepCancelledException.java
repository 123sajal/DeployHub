package com.deployhub.platform.execution;

import lombok.Getter;

/** A step stopped because its context was cancelled (user cancel, or this worker lost the job's lease). */
@Getter
public class StepCancelledException extends RuntimeException {

    private final ExecutionContext.CancelReason reason;

    public StepCancelledException(ExecutionContext.CancelReason reason) {
        super("Step stopped: " + reason);
        this.reason = reason;
    }
}
