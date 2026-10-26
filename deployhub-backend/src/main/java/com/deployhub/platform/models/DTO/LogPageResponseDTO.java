package com.deployhub.platform.models.DTO;

import com.deployhub.platform.models.enums.DeploymentStatus;

import java.util.List;

/**
 * A cursor page of log events. Pass {@code nextCursor} back as {@code after} to read on.
 * {@code deploymentStatus} lets a polling client stop once the deployment is finished.
 */
public record LogPageResponseDTO(
        List<DeploymentLogDTO> items,
        Long nextCursor,
        boolean hasMore,
        DeploymentStatus deploymentStatus) {
}
