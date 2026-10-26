package com.deployhub.platform.models.DTO;

import java.util.List;

/** "successful" counts LIVE + SUPERSEDED; "running" counts BUILDING + STARTING. */
public record DashboardSummaryResponseDTO(
        long totalProjects,
        long queued,
        long running,
        long successful,
        long failed,
        long cancelled,
        List<DeploymentResponseDTO> recentDeployments) {
}
