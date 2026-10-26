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
                deployment.getCommitRef(), deployment.getResolvedCommit(), appUrl(deployment),
                deployment.getFailureReason(),
                DeploymentStateMachine.canTransition(deployment.getStatus(), DeploymentStatus.CANCELLED),
                deployment.canBeRolledBackTo(), deployment.getRollbackOfId(), deployment.canBeDeleted(),
                deployment.getCreatedAt(), deployment.getStatusChangedAt(), deployment.getStartedAt(),
                deployment.getFinishedAt());
    }

    /**
     * While LIVE, what "Open app" opens: the project's public link when it has one; otherwise its fixed address
     * on this machine (which also applies its routes), or this deployment's own port before the project has one.
     */
    private static String appUrl(Deployment deployment) {
        if (deployment.getStatus() != DeploymentStatus.LIVE || deployment.getHostPort() == null) {
            return null;
        }
        if (deployment.getProject().isPublicLinkOn() && deployment.getProject().getPublicLinkUrl() != null) {
            return deployment.getProject().getPublicLinkUrl();
        }
        String projectUrl = ProjectMapper.publicUrl(deployment.getProject());
        return projectUrl != null ? projectUrl : "http://localhost:" + deployment.getHostPort();
    }

    public static DeploymentLogDTO toLog(DeploymentLog log) {
        return new DeploymentLogDTO(log.getId(), log.getLevel(), log.getMessage(), log.getOccurredAt());
    }
}
