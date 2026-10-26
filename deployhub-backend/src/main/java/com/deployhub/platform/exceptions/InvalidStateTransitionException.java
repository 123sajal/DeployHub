package com.deployhub.platform.exceptions;

import com.deployhub.platform.models.enums.DeploymentStatus;
import lombok.Getter;

@Getter
public class InvalidStateTransitionException extends ConflictException {

    private final DeploymentStatus from;
    private final DeploymentStatus to;

    public InvalidStateTransitionException(DeploymentStatus from, DeploymentStatus to) {
        super("Cannot change deployment status from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }
}
