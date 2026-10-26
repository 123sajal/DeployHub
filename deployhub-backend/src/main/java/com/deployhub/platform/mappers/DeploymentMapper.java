package com.deployhub.platform.mappers;

import com.deployhub.platform.models.DTO.DeploymentLogDTO;
import com.deployhub.platform.models.DTO.DeploymentResponseDTO;
import com.deployhub.platform.models.entities.Deployment;
import com.deployhub.platform.models.entities.DeploymentLog;
import com.deployhub.platform.models.enums.DeploymentStatus;
import com.deployhub.platform.deployment.DeploymentStateMachine;

public final class DeploymentMapper {

    private DeploymentMapper() {
    }

    public static DeploymentResponseDTO toResponse(Deployment deployment) {
        return new DeploymentResponseDTO(deployment.getId(), deployment.getProject().getId(),
                deployment.getProject().getName(), deployment.getStatus(), deployment.getExecutionMode(),
                deployment.getCommitRef(), deployment.getFailureReason(),
                DeploymentStateMachine.canTransition(deployment.getStatus(), DeploymentStatus.CANCELLED),
                deployment.canBeRolledBackTo(), deployment.getRollbackOfId(),
                deployment.getCreatedAt(), deployment.getStatusChangedAt(), deployment.getStartedAt(),
                deployment.getFinishedAt());
    }

    public static DeploymentLogDTO toLog(DeploymentLog log) {
        return new DeploymentLogDTO(log.getId(), log.getLevel(), log.getMessage(), log.getOccurredAt());
    }
}
