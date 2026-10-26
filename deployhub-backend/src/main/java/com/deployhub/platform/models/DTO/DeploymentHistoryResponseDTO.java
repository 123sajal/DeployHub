package com.deployhub.platform.models.DTO;

import com.deployhub.platform.models.enums.DeploymentStatus;
import com.deployhub.platform.models.enums.JobStatus;

import java.time.Instant;
import java.util.List;

public record DeploymentHistoryResponseDTO(Job job, List<Transition> transitions) {

    /**
     * @param nextAttemptAt set while a retry is scheduled
     * @param workerId      the worker currently holding the job, if any
     */
    public record Job(JobStatus status, int attempt, int maxAttempts, Instant nextAttemptAt, String lastError,
                      String workerId) {
    }

    public record Transition(DeploymentStatus fromStatus, DeploymentStatus toStatus, String reason, String actor,
                             Integer attempt, Instant occurredAt) {
    }
}
