package com.deployhub.platform.models.DTO;

import com.deployhub.platform.models.enums.DeploymentStatus;
import com.deployhub.platform.models.enums.ExecutionMode;

import java.time.Instant;

public record DeploymentResponseDTO(
        Long id,
        Long projectId,
        String projectName,
        DeploymentStatus status,
        ExecutionMode executionMode,
        String commitRef,
        String failureReason,
        boolean cancellable,
        /** True when a rollback to this deployment is possible (it was live and has been replaced). */
        boolean rollbackable,
        /** Set when this deployment is a rollback: the earlier deployment it redeploys. */
        Long rollbackOfId,
        Instant createdAt,
        Instant statusChangedAt,
        Instant startedAt,
        Instant finishedAt) {
}
